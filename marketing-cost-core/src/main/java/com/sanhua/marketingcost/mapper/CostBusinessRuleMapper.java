package com.sanhua.marketingcost.mapper;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CostBusinessRuleMapper {

  @Select("""
      SELECT decimal_value
        FROM lp_cost_business_rule
       WHERE rule_code = #{ruleCode}
         AND enabled = 1
         AND business_unit_type IN ('*', COALESCE(NULLIF(#{businessUnitType}, ''), '*'))
         AND effective_from <= #{pricingMonth}
         AND (effective_to IS NULL OR effective_to >= #{pricingMonth})
       ORDER BY CASE WHEN business_unit_type = #{businessUnitType} THEN 0 ELSE 1 END,
                effective_from DESC,
                id DESC
       LIMIT 1
      """)
  BigDecimal selectEffectiveDecimal(
      @Param("ruleCode") String ruleCode,
      @Param("pricingMonth") String pricingMonth,
      @Param("businessUnitType") String businessUnitType);
}
