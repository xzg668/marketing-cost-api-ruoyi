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

  /** 精确规则身份/月份选修订；不以 current_flag 代替历史月份及版本选择。 */
  @Select("""
      SELECT * FROM lp_price_range_factor_rule
      WHERE business_unit_type=#{key.businessUnitType} AND rule_identity_key=#{key.identityKey}
        AND pricing_month=#{pricingMonth} AND rule_status='PUBLISHED'
      ORDER BY version_no DESC LIMIT 1
      """)
  PriceRangeFactorRule findPublishedVersion(
      @Param("key") MaterialPriceRuleKey key, @Param("pricingMonth") String pricingMonth);

  /** 行情因素区间价规则按业务单元隔离。 */
  @DataScope
  @Override
  List<PriceRangeFactorRule> selectList(@Param("ew") Wrapper<PriceRangeFactorRule> queryWrapper);
}
