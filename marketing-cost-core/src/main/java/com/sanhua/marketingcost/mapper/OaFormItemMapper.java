package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OaFormItemMapper extends BaseMapper<OaFormItem> {

  /**
   * Acquire the product row before opening a snapshot read view; caller then verifies its owner.
   */
  @Select("SELECT * FROM oa_form_item WHERE id=#{itemId} AND COALESCE(deleted,0)=0 FOR UPDATE")
  OaFormItem selectForCostingWrite(@Param("itemId") Long itemId);

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<OaFormItem> selectList(@Param("ew") Wrapper<OaFormItem> queryWrapper);

  /**
   * 锁定待完成核算的产品行。
   *
   * <p>这里使用显式 SQL，而不通过 {@code selectOne + last("LIMIT 1 FOR UPDATE")}： {@link DataScope} 的 SQL
   * 重写会把锁子句序列化到 LIMIT 前面，形成 MySQL 不接受的 {@code FOR UPDATE LIMIT 1}。主键和 OA 表头共同限定唯一行，并显式带入业务单元，既保持
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
         AND (
           (material_no IS NOT NULL AND TRIM(material_no) <> '')
           OR (sunl_model IS NOT NULL AND TRIM(sunl_model) <> '')
           OR (customer_drawing IS NOT NULL AND TRIM(customer_drawing) <> '')
         )
      """)
  long countRunnableItems(@Param("oaFormId") Long oaFormId);

  @Select("""
      SELECT COUNT(*)
        FROM oa_form_item
       WHERE oa_form_id = #{oaFormId}
         AND COALESCE(deleted, 0) = 0
         AND (
           (material_no IS NOT NULL AND TRIM(material_no) <> '')
           OR (sunl_model IS NOT NULL AND TRIM(sunl_model) <> '')
           OR (customer_drawing IS NOT NULL AND TRIM(customer_drawing) <> '')
         )
         AND calc_status = '已核算'
      """)
  long countCalculatedRunnableItems(@Param("oaFormId") Long oaFormId);

  @Select("""
      SELECT * FROM oa_form_item WHERE oa_form_id = #{formId} ORDER BY id FOR UPDATE
      """)
  List<OaFormItem> lockOaSourceItems(@Param("formId") Long formId);

  /** OA 重推的来源字段全量采用，包含本次清空的字段。 */
  @Update("""
      UPDATE oa_form_item
         SET seq = #{source.seq},
             product_name = #{source.productName},
             customer_drawing = #{source.customerDrawing},
             customer_code = #{source.customerCode},
             material_no = #{source.materialNo},
             sunl_model = #{source.sunlModel},
             spec = #{source.spec},
             product_attr = #{source.productAttr},
             business_type = #{source.businessType},
             first_quote_flag = #{source.firstQuoteFlag},
             certification_required = #{source.certificationRequired},
             origin_country = #{source.originCountry},
             technician_name = #{source.technicianName},
             package_type = #{source.packageType},
             package_method = #{source.packageMethod},
             package_component_code = #{source.packageComponentCode},
             package_qty = #{source.packageQty},
             shipping_fee = #{source.shippingFee},
             support_qty = #{source.supportQty},
             annual_volume = #{source.annualVolume},
             project_no = #{source.projectNo},
             product_status = #{source.productStatus},
             scrap_rate = #{source.scrapRate},
             unit_labor_cost = #{source.unitLaborCost},
             total_with_ship = #{source.totalWithShip},
             total_no_ship = #{source.totalNoShip},
             material_cost = #{source.materialCost},
             labor_cost = #{source.laborCost},
             manufacturing_cost = #{source.manufacturingCost},
             management_cost = #{source.managementCost},
             valid_month = #{source.validMonth},
             sus304_weight_g = #{source.sus304WeightG},
             sus316_weight_g = #{source.sus316WeightG},
             copper_weight_g = #{source.copperWeightG},
             classification_status = #{source.classificationStatus},
             business_unit_type = #{source.businessUnitType},
             valid_date = #{source.validDate},
             updated_at = #{source.updatedAt},
             deleted = 0
       WHERE id = #{source.id} AND oa_form_id = #{source.oaFormId}
      """)
  int updateOaSourceItem(@Param("source") OaFormItem source);
}
