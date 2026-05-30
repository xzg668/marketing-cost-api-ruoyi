package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OaFormItemMapper extends BaseMapper<OaFormItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<OaFormItem> selectList(@Param("ew") Wrapper<OaFormItem> queryWrapper);

  /**
   * T3：月度调价只按 OA 已核算状态展开范围，不从 lp_cost_run_result 反推。
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
}
