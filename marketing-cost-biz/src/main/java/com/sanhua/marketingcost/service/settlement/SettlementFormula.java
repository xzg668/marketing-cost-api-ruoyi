package com.sanhua.marketingcost.service.settlement;

/** 本次已确认的公式契约，由原件适配器和纯计算器共同使用。业务系数仍来自每条规则。 */
public final class SettlementFormula {
  public static final String VERSION = "SETTLEMENT_CU_ZN_V1";
  public static final String EXPRESSION = "plannedPrice * markupRatio + ((settlementCu - budgetCu) * copperWeightG"
      + " + (settlementZn - budgetZn) * zincWeightG) / 1000 / taxDivisor * markupRatio";
  public static final String ROUNDED_VERSION = "SETTLEMENT_CU_ZN_ROUND4_V2";
  public static final String ROUNDED_EXPRESSION = "ROUND(" + EXPRESSION + ", 4)";
  private SettlementFormula() {}
}
