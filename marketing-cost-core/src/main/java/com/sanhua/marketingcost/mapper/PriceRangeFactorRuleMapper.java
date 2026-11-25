package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PriceRangeFactorRuleMapper extends BaseMapper<PriceRangeFactorRule> {

  // An older published row remains available to history; a withdrawn newer rule masks it for price lookup.
  String NOT_MASKED_BY_WITHDRAWAL = """
      NOT EXISTS (SELECT 1 FROM lp_price_range_factor_rule n
        WHERE n.business_unit_type=p.business_unit_type AND n.org_code=p.org_code
          AND n.pricing_month=p.pricing_month AND n.rule_identity_key=p.rule_identity_key
          AND n.rule_status='VOIDED'
          AND COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(n.source_input_snapshot_json),
            n.source_input_snapshot_json,'{}'),'$.ruleSetRevision')) AS UNSIGNED),n.version_no)
          >COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(p.source_input_snapshot_json),
            p.source_input_snapshot_json,'{}'),'$.ruleSetRevision')) AS UNSIGNED),p.version_no))
      """;
  /** 精确规则身份/月份选修订；不以 current_flag 代替历史月份及版本选择。 */
  @Select("""
      SELECT p.* FROM lp_price_range_factor_rule p
      WHERE business_unit_type=#{key.businessUnitType} AND rule_identity_key=#{key.identityKey}
        AND pricing_month=#{pricingMonth} AND rule_status='PUBLISHED' AND
      """ + NOT_MASKED_BY_WITHDRAWAL + " ORDER BY version_no DESC LIMIT 1")
  PriceRangeFactorRule findPublishedVersion(
      @Param("key") MaterialPriceRuleKey key, @Param("pricingMonth") String pricingMonth);

  /** 行情因素区间价规则按业务单元隔离。 */
  @DataScope
  @Override
  List<PriceRangeFactorRule> selectList(@Param("ew") Wrapper<PriceRangeFactorRule> queryWrapper);
}
