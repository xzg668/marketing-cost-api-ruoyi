package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.mapper.BomByproductCostRuleMapper;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("BomByproductCostRuleQueryService · 新副产品规则查询服务")
class BomByproductCostRuleQueryServiceTest {

  @Test
  @DisplayName("从新 Mapper 读取 enabled 候选，再由新 Matcher 处理 addConditionType 和 JSON 条件")
  @SuppressWarnings("unchecked")
  void readsEnabledCandidatesAndMatches() {
    BomByproductCostRuleMapper mapper = Mockito.mock(BomByproductCostRuleMapper.class);
    BomByproductCostRuleMatcher matcher =
        new BomByproductCostRuleMatcher(
            new BomByproductCostRuleConditionEvaluator(new com.fasterxml.jackson.databind.ObjectMapper()));
    BomByproductCostRuleQueryServiceImpl service =
        new BomByproductCostRuleQueryServiceImpl(mapper, matcher);
    BomByproductCostRule rule = rule("BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF", 10);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));

    assertThat(service.match(
            node(), "NO_SCRAP_REF_MATCH", "主制造", LocalDate.of(2026, 5, 29)))
        .contains(rule);
    verify(mapper).selectList(any(Wrapper.class));
  }

  private static BomByproductCostRule rule(String code, int priority) {
    BomByproductCostRule rule = new BomByproductCostRule();
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");
    rule.setSettlementRowType("BYPRODUCT_EXTRA");
    rule.setMatchConditionJson("""
        {"byproductConditions":[{"field":"shape_attr","op":"EQ","value":"制造件"}]}
        """);
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomRuleNodeContext node() {
    return new BomRuleNodeContext(
        "P001", "副产品", "31", "副产品", null, "制造件", "BY01", "制造件", "COMMERCIAL", "主制造");
  }
}
