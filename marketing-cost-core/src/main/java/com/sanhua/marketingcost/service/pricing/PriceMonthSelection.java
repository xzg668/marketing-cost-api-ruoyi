package com.sanhua.marketingcost.service.pricing;

/** 规则和因素都记录请求月份与实际来源月份，禁止未来月份参与取价。 */
public record PriceMonthSelection(String requestedMonth, String sourceMonth) {
  public PriceMonthSelection {
    requestedMonth = FactorPriceScope.month(requestedMonth);
    sourceMonth = FactorPriceScope.month(sourceMonth);
    if (sourceMonth.compareTo(requestedMonth) > 0) {
      throw new IllegalArgumentException("不能使用未来月份价格：请求=" + requestedMonth + "，来源=" + sourceMonth);
    }
  }

  public boolean carriedForward() {
    return !requestedMonth.equals(sourceMonth);
  }
}
