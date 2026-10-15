package com.sanhua.marketingcost.formula.registry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** 公式金标的独立财务输入；发布选择、月份及单位契约由真实查询测试覆盖。 */
public final class FinanceVariableTestSupport {
  private FinanceVariableTestSupport() {}

  public static FinanceFactorVariableQuery prices(Map<String, BigDecimal> values) {
    var query = mock(FinanceFactorVariableQuery.class);
    when(query.query(any(), any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> params = invocation.getArgument(0);
              VariableContext context = invocation.getArgument(1);
              Object key = params.getOrDefault("factorCode", params.get("shortName"));
              return Optional.ofNullable(values.get(key))
                  .map(
                      price ->
                          new FinanceFactorVariableQuery.Price(
                              price, context.getPricingMonth(), 1L, "FINANCE_FACTOR"));
            });
    when(query.queryName(any(), any()))
        .thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));
    return query;
  }
}
