package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.mapper.BomByproductCostRuleMapper;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 新 BOM 副产品附加规则查询服务。
 *
 * <p>统一读取 enabled 规则并按 priority 排序；业务单元、BOM 用途、生效期、
 * add_condition_type 和 JSON 条件由 {@link BomByproductCostRuleMatcher} 在命中阶段统一处理。
 */
@Service
public class BomByproductCostRuleQueryServiceImpl implements BomByproductCostRuleQueryService {

  private final BomByproductCostRuleMapper ruleMapper;
  private final BomByproductCostRuleMatcher matcher;

  public BomByproductCostRuleQueryServiceImpl(
      BomByproductCostRuleMapper ruleMapper,
      BomByproductCostRuleMatcher matcher) {
    this.ruleMapper = ruleMapper;
    this.matcher = matcher;
  }

  @Override
  public List<BomByproductCostRule> listEnabledCandidates() {
    return ruleMapper.selectList(
        Wrappers.<BomByproductCostRule>lambdaQuery()
            .eq(BomByproductCostRule::getEnabled, 1)
            .orderByAsc(BomByproductCostRule::getPriority));
  }

  @Override
  public Optional<BomByproductCostRule> match(
      BomRuleNodeContext byproductContext,
      String addConditionType,
      String bomPurpose,
      LocalDate asOfDate) {
    return matcher.match(
        byproductContext, addConditionType, bomPurpose, asOfDate, listEnabledCandidates());
  }
}
