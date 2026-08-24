package com.sanhua.marketingcost.formula.registry.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.FinanceBasePriceQuery;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class FinanceBaseResolverTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void usesSharedLatestFormalPriceQuery() {
    FinanceBasePriceQuery query = mock(FinanceBasePriceQuery.class);
    FinanceBasePrice carried = new FinanceBasePrice();
    carried.setPriceMonth("2026-07");
    carried.setPrice(new BigDecimal("389.993"));
    when(query.queryLatestBasePrice(
            "Sn", null, "财务报价", true, "2026-08", "COMMERCIAL", "Sn"))
        .thenReturn(Optional.of(carried));

    var authentication = new UsernamePasswordAuthenticationToken("tester", "n/a");
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    PriceVariable variable = new PriceVariable();
    variable.setVariableCode("Sn");
    variable.setContextBindingJson("{\"factorCode\":\"Sn\",\"priceSource\":\"财务报价\"}");
    FinanceBaseResolver resolver = new FinanceBaseResolver(query, new ObjectMapper());

    assertThat(resolver.resolve(variable, new VariableContext().pricingMonth("2026-08")))
        .isEqualByComparingTo("389.993");
    verify(query).queryLatestBasePrice(
        "Sn", null, "财务报价", true, "2026-08", "COMMERCIAL", "Sn");
  }
}
