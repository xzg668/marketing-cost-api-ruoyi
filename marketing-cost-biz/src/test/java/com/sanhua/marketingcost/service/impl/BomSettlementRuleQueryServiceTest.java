package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("BomSettlementRuleQueryService · 新树节点规则查询服务")
class BomSettlementRuleQueryServiceTest {

  @Test
  @DisplayName("从新 Mapper 读取 enabled 候选，再由新 Matcher 处理作用域、生效期和 JSON 条件")
  @SuppressWarnings("unchecked")
  void readsEnabledCandidatesAndMatches() {
    BomSettlementRuleMapper mapper = Mockito.mock(BomSettlementRuleMapper.class);
    BomSettlementRuleMatcher matcher =
        new BomSettlementRuleMatcher(
            new BomSettlementRuleConditionEvaluator(new com.fasterxml.jackson.databind.ObjectMapper()));
    BomSettlementRuleQueryServiceImpl service =
        new BomSettlementRuleQueryServiceImpl(mapper, matcher);
    BomSettlementRule rule = rule("SPECIAL_PURCHASE_ROLLUP_MESH", 10);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));

    assertThat(service.match(node(), null, List.of(), null, LocalDate.of(2026, 5, 29)))
        .contains(rule);
    verify(mapper).selectList(any(Wrapper.class));
  }

  private static BomSettlementRule rule(String code, int priority) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setMatchConditionJson("""
        {"nodeConditions":[{"field":"material_name","op":"LIKE","value":"丝网"}]}
        """);
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomRuleNodeContext node() {
    return new BomRuleNodeContext(
        "X", "丝网件", "18", "辅料", "丝网", "制造件", "RM01", "采购件", "COMMERCIAL", null);
  }
}
