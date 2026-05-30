package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 新 BOM 树节点结算规则查询服务。
 *
 * <p>统一读取 enabled 规则并按 priority 排序；业务单元、BOM 用途、生效期和 JSON 条件由
 * {@link BomSettlementRuleMatcher} 在命中阶段统一处理。
 */
@Service
public class BomSettlementRuleQueryServiceImpl implements BomSettlementRuleQueryService {

  private final BomSettlementRuleMapper ruleMapper;
  private final BomSettlementRuleMatcher matcher;

  public BomSettlementRuleQueryServiceImpl(
      BomSettlementRuleMapper ruleMapper,
      BomSettlementRuleMatcher matcher) {
    this.ruleMapper = ruleMapper;
    this.matcher = matcher;
  }

  @Override
  public List<BomSettlementRule> listEnabledCandidates() {
    return ruleMapper.selectList(
        Wrappers.<BomSettlementRule>lambdaQuery()
            .eq(BomSettlementRule::getEnabled, 1)
            .orderByAsc(BomSettlementRule::getPriority));
  }

  @Override
  public Optional<BomSettlementRule> match(
      BomRuleNodeContext node,
      BomRuleNodeContext parent,
      List<BomRuleNodeContext> children,
      String bomPurpose,
      LocalDate asOfDate) {
    return matcher.match(node, parent, children, bomPurpose, asOfDate, listEnabledCandidates());
  }
}
