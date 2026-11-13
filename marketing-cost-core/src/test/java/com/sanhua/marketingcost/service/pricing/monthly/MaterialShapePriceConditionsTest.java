package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MaterialShapePriceConditionsTest {
  @Test void ordinaryAnyDoesNotReusePurchaseOrOutsourcePrice() {
    var ordinary = ConditionDomain.any();
    assertThat(ordinary.matches(NonCuConditions.empty())).isTrue();
    assertThat(ordinary.matches(new NonCuConditions(null,"PURCHASE:1"))).isFalse();
    assertThat(ordinary.matches(new NonCuConditions(null,"OUTSOURCE:1"))).isFalse();
  }
  @Test void scopedAnyRetainsBoardTermsAndOnlyMatchesItsShapeRevision() {
    var purchase = ConditionDomain.any().forMaterialShape("PURCHASE:1");
    assertThat(purchase.matches(new NonCuConditions(null,"PURCHASE:1"))).isTrue();
    assertThat(purchase.matches(new NonCuConditions(new BoardTerms(null,null,true),"PURCHASE:1"))).isTrue();
    assertThat(purchase.matches(new NonCuConditions(null,"PURCHASE:2"))).isFalse();
    assertThat(purchase.matches(new NonCuConditions(null,"OUTSOURCE:1"))).isFalse();
  }
  @Test void separateDomainsDoNotCompeteOrSubtractEachOther() {
    var original = ConditionDomain.any();
    var purchase = original.forMaterialShape("PURCHASE:1");
    var outsource = original.forMaterialShape("OUTSOURCE:1");
    assertThat(original.intersection(purchase)).isEmpty();
    assertThat(purchase.intersection(outsource)).isEmpty();
    assertThat(original.subtract(purchase)).contains(original);
    assertThat(purchase.subtract(original)).contains(purchase);
  }
  @Test void ordinaryCanonicalContractStaysExactlyTheSame() {
    assertThat(ConditionDomain.any().canonical()).isEqualTo(MonthlyPriceCanonical.fields("CONDITION_DOMAIN_V1","ANY"));
    assertThat(ConditionDomain.exact(NonCuConditions.empty()).canonical()).isEqualTo(
        MonthlyPriceCanonical.fields("CONDITION_DOMAIN_V1","EXACT",MonthlyPriceCanonical.fields("NON_CU_CONDITIONS_V1","ABSENT")));
  }
  @Test void scopedExactAndExcludingRetainSetAlgebra() {
    var value = new NonCuConditions(null,"OUTSOURCE:1");
    var exact = ConditionDomain.exact(value);
    var any = ConditionDomain.any().forMaterialShape("OUTSOURCE:1");
    assertThat(any.intersection(exact)).contains(exact);
    var remainder = any.subtract(exact).orElseThrow();
    assertThat(remainder.matches(value)).isFalse();
    assertThat(remainder.matches(new NonCuConditions(new BoardTerms(null,null,true),"OUTSOURCE:1"))).isTrue();
    assertThat(exact.subtract(any)).isEmpty();
    assertThat(remainder.intersection(exact)).isEmpty();
  }
}
