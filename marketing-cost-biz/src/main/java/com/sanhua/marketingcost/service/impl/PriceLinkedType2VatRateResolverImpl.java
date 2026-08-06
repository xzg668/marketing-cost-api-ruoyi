package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.service.PriceLinkedType2VatRateResolver;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PriceLinkedType2VatRateResolverImpl
    implements PriceLinkedType2VatRateResolver {

  private final FactorVariableRegistry factorVariableRegistry;

  public PriceLinkedType2VatRateResolverImpl(
      FactorVariableRegistry factorVariableRegistry) {
    this.factorVariableRegistry = factorVariableRegistry;
  }

  @Override
  public Optional<BigDecimal> resolve(PriceLinkedType2MergedRow mergedRow) {
    if (mergedRow == null) {
      return Optional.empty();
    }
    VariableContext context = new VariableContext()
        .materialCode(mergedRow.getMaterialCode())
        .pricingMonth(mergedRow.getPricingMonth())
        .priceContextType(VariableContext.PriceContextType.MONTHLY_REPRICE);
    return factorVariableRegistry.resolve("vat_rate", context);
  }
}
