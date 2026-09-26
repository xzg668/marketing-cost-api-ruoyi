package com.sanhua.marketingcost.support;

import com.sanhua.marketingcost.entity.BomSettlementRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class FinancePurchaseRollupRuleTestSupport {
  private FinancePurchaseRollupRuleTestSupport() {}

  public static BomSettlementRule rule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(224L);
    rule.setRuleCode("SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION");
    rule.setRuleName("特殊采购分类上卷：母件副产品规则");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    try (InputStream input = FinancePurchaseRollupRuleTestSupport.class
        .getResourceAsStream("/fixtures/bom/finance_purchase_rollup_rule.json")) {
      if (input == null) {
        throw new IllegalStateException("缺少财务上卷规则测试数据");
      }
      rule.setMatchConditionJson(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("读取财务上卷规则测试数据失败", exception);
    }
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(10);
    rule.setEnabled(1);
    return rule;
  }
}
