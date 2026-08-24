package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteBomRuleFingerprintServiceImplTest {

  private final BomSettlementRuleQueryService settlement =
      mock(BomSettlementRuleQueryService.class);
  private final BomByproductCostRuleQueryService byproduct =
      mock(BomByproductCostRuleQueryService.class);
  private final QuoteBomRuleFingerprintServiceImpl service =
      new QuoteBomRuleFingerprintServiceImpl(settlement, byproduct);

  @Test
  void sameRulesHaveStableFingerprintRegardlessOfQueryOrder() {
    BomSettlementRule first = settlement(1L, "A", 10);
    BomSettlementRule second = settlement(2L, "B", 20);
    when(settlement.listEnabledCandidates()).thenReturn(List.of(first, second), List.of(second, first));
    when(byproduct.listEnabledCandidates()).thenReturn(List.of());

    assertThat(service.currentFingerprint()).isEqualTo(service.currentFingerprint());
  }

  @Test
  void semanticRuleChangeChangesFingerprint() {
    BomSettlementRule rule = settlement(1L, "A", 10);
    when(settlement.listEnabledCandidates()).thenReturn(List.of(rule));
    when(byproduct.listEnabledCandidates()).thenReturn(List.of());
    String before = service.currentFingerprint();

    rule.setPriority(11);

    assertThat(service.currentFingerprint()).isNotEqualTo(before);
  }

  @Test
  void byproductRuleParticipatesInFingerprint() {
    BomByproductCostRule rule = new BomByproductCostRule();
    rule.setId(9L);
    rule.setRuleCode("BY-1");
    rule.setAddConditionType("SOURCE_EXISTS");
    rule.setPriority(3);
    when(settlement.listEnabledCandidates()).thenReturn(List.of());
    when(byproduct.listEnabledCandidates()).thenReturn(List.of(), List.of(rule));

    assertThat(service.currentFingerprint()).isNotEqualTo(service.currentFingerprint());
  }

  private BomSettlementRule settlement(Long id, String code, int priority) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(id);
    rule.setRuleCode(code);
    rule.setSettlementAction("KEEP");
    rule.setPriority(priority);
    return rule;
  }
}
