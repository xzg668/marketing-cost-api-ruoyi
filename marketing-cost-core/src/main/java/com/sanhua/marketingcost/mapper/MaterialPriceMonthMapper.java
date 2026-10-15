package com.sanhua.marketingcost.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 原稿月份是业务单元/取价组织的范围属性，不能按料号分别回退。 */
@Mapper
public interface MaterialPriceMonthMapper {
  // V315覆盖选月的全部字段；刚批量导入时统计信息可能滞后，避免优化器回表读取大JSON。
  @Select(
      """
      SELECT MAX(pricing_month) FROM (
        SELECT pricing_month FROM lp_price_linked_item FORCE INDEX (idx_linked_scope_month)
        WHERE business_unit_type=#{businessUnit} AND COALESCE(org_code,'')=#{orgCode}
          AND source_kind='PUBLIC' AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED')
          AND pricing_month<=#{requestedMonth}
        UNION ALL
        SELECT pricing_month FROM lp_price_range_factor_rule FORCE INDEX (idx_range_scope_month)
        WHERE business_unit_type=#{businessUnit} AND COALESCE(org_code,'')=#{orgCode}
          AND rule_status IN ('LEGACY','PUBLISHED') AND pricing_month<=#{requestedMonth}
      ) available_months
      """)
  String findLatestMonth(
      @Param("businessUnit") String businessUnit,
      @Param("orgCode") String orgCode,
      @Param("requestedMonth") String requestedMonth);

  @Select(
      """
      SELECT COUNT(*) FROM lp_price_range_factor_rule
      WHERE business_unit_type=#{businessUnit} AND COALESCE(org_code,'')=#{orgCode}
        AND pricing_month=#{sourceMonth} AND material_code=#{materialCode}
        AND rule_status IN ('LEGACY','PUBLISHED')
      """)
  int countRangeRules(
      @Param("businessUnit") String businessUnit,
      @Param("orgCode") String orgCode,
      @Param("sourceMonth") String sourceMonth,
      @Param("materialCode") String materialCode);
}
