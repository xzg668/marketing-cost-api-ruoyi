package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OaFormItemMapper extends BaseMapper<OaFormItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<OaFormItem> selectList(@Param("ew") Wrapper<OaFormItem> queryWrapper);

  /**
   * 锁定待完成核算的产品行。
   *
   * <p>这里使用显式 SQL，而不通过 {@code selectOne + last("LIMIT 1 FOR UPDATE")}：
   * {@link DataScope} 的 SQL 重写会把锁子句序列化到 LIMIT 前面，形成 MySQL 不接受的
   * {@code FOR UPDATE LIMIT 1}。主键和 OA 表头共同限定唯一行，并显式带入业务单元，既保持
   * 数据隔离，也保证锁语句稳定为 {@code LIMIT 1 FOR UPDATE}。
   */
  @Select("""
      SELECT *
        FROM oa_form_item
       WHERE id = #{itemId}
         AND oa_form_id = #{oaFormId}
         AND COALESCE(deleted, 0) = 0
         AND business_unit_type = #{businessUnitType}
       LIMIT 1
       FOR UPDATE
      """)
  OaFormItem selectForCostCompletion(
      @Param("itemId") Long itemId,
      @Param("oaFormId") Long oaFormId,
      @Param("businessUnitType") String businessUnitType);

  /**
   * T3：月度调价只按 OA 已核算状态展开范围，不从成本历史版本反推。
   *
   * <p>客户名称当前稳定来源是 OA 表头 customer；产品料号和包装方式来源于 OA 明细行。
   */
  @Select("""
      SELECT
        f.oa_no AS oaNo,
        i.id AS oaFormItemId,
        i.material_no AS productCode,
        i.package_method AS packageMethod,
        f.customer AS customerName,
        f.calc_status AS sourceOaCalcStatus
      FROM oa_form f
      INNER JOIN oa_form_item i
        ON i.oa_form_id = f.id
       AND COALESCE(i.deleted, 0) = 0
      WHERE COALESCE(f.deleted, 0) = 0
        AND f.business_unit_type = #{businessUnitType}
        AND f.calc_status = #{calcStatus}
      ORDER BY f.oa_no ASC, i.seq ASC, i.id ASC
      """)
  List<MonthlyRepriceCalcObject> selectMonthlyRepriceCalcObjects(
      @Param("businessUnitType") String businessUnitType,
      @Param("calcStatus") String calcStatus);

  @Update("""
      UPDATE oa_form_item
         SET calc_status = '已核算',
             calc_at = #{calcAt},
             updated_at = #{calcAt}
       WHERE id = #{itemId}
         AND COALESCE(deleted, 0) = 0
      """)
  int markCalculated(@Param("itemId") Long itemId, @Param("calcAt") LocalDateTime calcAt);

  @Select("""
      SELECT COUNT(*)
        FROM oa_form_item
       WHERE oa_form_id = #{oaFormId}
         AND COALESCE(deleted, 0) = 0
         AND material_no IS NOT NULL
         AND TRIM(material_no) <> ''
      """)
  long countRunnableItems(@Param("oaFormId") Long oaFormId);

  @Select("""
      SELECT COUNT(*)
        FROM oa_form_item
       WHERE oa_form_id = #{oaFormId}
         AND COALESCE(deleted, 0) = 0
         AND material_no IS NOT NULL
         AND TRIM(material_no) <> ''
         AND calc_status = '已核算'
      """)
  long countCalculatedRunnableItems(@Param("oaFormId") Long oaFormId);
}
