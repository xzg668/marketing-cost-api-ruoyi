package com.sanhua.marketingcost.service.pricing.monthly;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyAnnualUsageCoverageTest {
  static QuantityInterval interval(String low, String high) {
    return new QuantityInterval(low == null ? null : new BigDecimal(low), true,
        high == null ? null : new BigDecimal(high), false);
  }
  static MonthlyPriceRequest request(String annual) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",
        "001M",null,"只","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE,
        annual == null ? null : new BigDecimal(annual));
  }
  static MonthlyPriceCoverage ordinary() {
    return new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any());
  }
  static MonthlyPriceCoverage tier() {
    return new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any(),
        AnnualUsageDomain.known(interval("240000","2400000")));
  }

  @Test void tierDemandDoesNotUseTheBomConsumptionQuantity() {
    assertTrue(tier().matches(request("360000")));
    assertFalse(tier().matches(request("120000")));
    assertFalse(tier().matches(request(null)));
    assertTrue(ordinary().matches(request(null)));
  }
  @Test void exactAnnualBoundsPreserveMonthlyLowerInclusiveUpperExclusiveWithoutDivision() {
    assertTrue(tier().matches(request("240000")));
    assertTrue(tier().matches(request("2399999.999999999999")));
    assertFalse(tier().matches(request("2400000")));
  }
  @Test void removingAnAdoptedTierDoesNotLoseUnknownDemandForNewProductsWithFixedPrices() {
    var remaining = MonthlyPriceApplicability.of(ordinary()).subtract(MonthlyPriceApplicability.of(tier())).orElseThrow();
    assertTrue(remaining.matches(request(null)));
    assertTrue(remaining.matches(request("120000")));
    assertTrue(remaining.matches(request("2400000")));
    assertFalse(remaining.matches(request("360000")));
  }
  @Test void adoptedGeneralFixedPriceOccupiesAllDemandsIncludingUnknown() {
    assertTrue(MonthlyPriceApplicability.of(tier()).subtract(MonthlyPriceApplicability.of(ordinary())).isEmpty());
    assertEquals(1,ordinary().intersection(tier()).orElseThrow().annualUsage().quantities().lower().compareTo(BigDecimal.ZERO));
  }
  @Test void legacyCoverageJsonRetainsItsCanonicalIdentity() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    String old = "{\"organizations\":[\"210\"],\"quantities\":{\"lower\":null,\"lowerInclusive\":false,\"upper\":null,\"upperInclusive\":false},\"conditions\":{\"mode\":\"ANY\",\"values\":[]}}";
    var decoded = json.readValue(old,MonthlyPriceCoverage.class);
    assertEquals(ordinary(),decoded);
    assertEquals(ordinary().canonical(),decoded.canonical());
    assertTrue(decoded.matches(request(null)));
    assertEquals(tier(),json.readValue(json.writeValueAsString(tier()),MonthlyPriceCoverage.class));
  }
  @Test void unknownOnlyDomainIsPreservedAndHasNoNumericOverlap() {
    var unknown = new AnnualUsageDomain(null,true);
    assertTrue(unknown.matches(null)); assertFalse(unknown.matches(BigDecimal.ONE));
    assertTrue(unknown.intersection(AnnualUsageDomain.known(QuantityInterval.all())).isEmpty());
    assertEquals(List.of(unknown),AnnualUsageDomain.any().subtract(AnnualUsageDomain.known(QuantityInterval.all())));
  }
  @Test void subtractionIsDisjointAndExactAcrossEveryExistingAxisAndAnnualDemand() {
    var original = new MonthlyPriceCoverage(Set.of("210","220"),interval("0","10"),ConditionDomain.any());
    var remove = new MonthlyPriceCoverage(Set.of("210"),interval("2","8"),ConditionDomain.exact(NonCuConditions.empty()),
        AnnualUsageDomain.known(interval("12","120")));
    var rest = original.subtract(remove);
    for (int i=0;i<rest.size();i++) for(int j=i+1;j<rest.size();j++) assertTrue(rest.get(i).intersection(rest.get(j)).isEmpty());
    for (var org : List.of("210","220")) for (int q=0;q<=10;q++) for(var annual:Arrays.asList(null,"0","12","50","120","1000")) {
      var base=request(annual);var r=new MonthlyPriceRequest(base.family(),org,base.conditions(),BigDecimal.valueOf(q),base.annualUsagePieces());
      assertEquals(original.matches(r)&&!remove.matches(r),rest.stream().anyMatch(c->c.matches(r)),r.toString());
    }
  }
  @Test void invalidOptionalAnnualInputsDoNotBecomeAnEarlyValidationGate() {
    assertNull(new AnnualUsageInput(null,null).piecesIfKnown());
    assertNull(new AnnualUsageInput(BigDecimal.ZERO,"PIECES").piecesIfKnown());
    assertNull(new AnnualUsageInput(BigDecimal.ONE,"UNKNOWN").piecesIfKnown());
    assertEquals(new BigDecimal("30000").stripTrailingZeros(),new AnnualUsageInput(new BigDecimal("3"),"TEN_THOUSAND_PIECES").piecesIfKnown());
  }
}
