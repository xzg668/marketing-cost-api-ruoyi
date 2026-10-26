package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.util.*;

/** Annual demand is independent of BOM quantity; an unknown demand is an explicit domain member. */
public record AnnualUsageDomain(QuantityInterval quantities, boolean includesUnknown) {
  public AnnualUsageDomain {
    if (quantities == null && !includesUnknown) throw new IllegalArgumentException("Empty annual usage domain");
  }
  public static AnnualUsageDomain any() { return new AnnualUsageDomain(QuantityInterval.all(), true); }
  public static AnnualUsageDomain known(QuantityInterval interval) { return new AnnualUsageDomain(Objects.requireNonNull(interval), false); }
  public boolean matches(BigDecimal annualPieces) {
    return annualPieces == null ? includesUnknown : quantities != null && quantities.contains(annualPieces);
  }
  public Optional<AnnualUsageDomain> intersection(AnnualUsageDomain other) {
    var common = quantities == null || other.quantities == null ? Optional.<QuantityInterval>empty()
        : quantities.intersection(other.quantities);
    boolean unknown = includesUnknown && other.includesUnknown;
    return common.isEmpty() && !unknown ? Optional.empty() : Optional.of(new AnnualUsageDomain(common.orElse(null), unknown));
  }
  public List<AnnualUsageDomain> subtract(AnnualUsageDomain other) {
    var remaining = quantities == null ? List.<QuantityInterval>of()
        : other.quantities == null ? List.of(quantities) : quantities.subtract(other.quantities);
    boolean unknown = includesUnknown && !other.includesUnknown;
    var result = new ArrayList<AnnualUsageDomain>();
    for (var interval : remaining) { result.add(new AnnualUsageDomain(interval, unknown)); unknown = false; }
    if (unknown) result.add(new AnnualUsageDomain(null, true));
    return List.copyOf(result);
  }
  public String canonical() {
    return MonthlyPriceCanonical.fields("ANNUAL_USAGE_DOMAIN_V1", Boolean.toString(includesUnknown),
        quantities == null ? null : MonthlyPriceCanonical.number(quantities.lower()),
        quantities == null ? null : Boolean.toString(quantities.lowerInclusive()),
        quantities == null ? null : MonthlyPriceCanonical.number(quantities.upper()),
        quantities == null ? null : Boolean.toString(quantities.upperInclusive()));
  }
}
