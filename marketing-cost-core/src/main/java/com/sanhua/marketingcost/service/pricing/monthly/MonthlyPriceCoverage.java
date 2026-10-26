package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable applicability of one adopted financial fact. */
public record MonthlyPriceCoverage(
    Set<String> organizations, QuantityInterval quantities, ConditionDomain conditions,
    AnnualUsageDomain annualUsage) {

  public MonthlyPriceCoverage(Set<String> organizations, QuantityInterval quantities, ConditionDomain conditions) {
    this(organizations, quantities, conditions, AnnualUsageDomain.any());
  }

  public MonthlyPriceCoverage {
    organizations = organizations.stream()
        .map(value -> MaterialOrganization.fromPriceOrgCode(value).getPriceOrgCode())
        .collect(Collectors.toUnmodifiableSet());
    if (organizations.isEmpty()) {
      throw new IllegalArgumentException("At least one applicable organization is required");
    }
    Objects.requireNonNull(quantities, "Quantity coverage is required");
    Objects.requireNonNull(conditions, "Condition coverage is required");
    annualUsage = annualUsage == null ? AnnualUsageDomain.any() : annualUsage;
  }

  /** The caller first selects the matching family; coverage has no independent family. */
  public boolean matches(MonthlyPriceRequest request) {
    Objects.requireNonNull(request, "Monthly price request is required");
    return organizations.contains(request.priceOrgCode())
        && quantities.contains(request.priceBasisQuantity())
        && conditions.matches(request.conditions())
        && annualUsage.matches(request.annualUsagePieces());
  }

  public Optional<MonthlyPriceCoverage> intersection(MonthlyPriceCoverage other) {
    Objects.requireNonNull(other, "Other price coverage is required");
    var commonOrganizations = new HashSet<>(organizations);
    commonOrganizations.retainAll(other.organizations);
    if (commonOrganizations.isEmpty()) return Optional.empty();
    var commonQuantities = quantities.intersection(other.quantities);
    var commonConditions = conditions.intersection(other.conditions);
    var commonAnnual = annualUsage.intersection(other.annualUsage);
    if (commonQuantities.isEmpty() || commonConditions.isEmpty() || commonAnnual.isEmpty()) return Optional.empty();
    return Optional.of(new MonthlyPriceCoverage(
        commonOrganizations, commonQuantities.get(), commonConditions.get(), commonAnnual.get()));
  }

  public List<MonthlyPriceCoverage> subtract(MonthlyPriceCoverage other) {
    var overlap = intersection(other);
    if (overlap.isEmpty()) return List.of(this);
    MonthlyPriceCoverage covered = overlap.get();
    var remaining = new ArrayList<MonthlyPriceCoverage>();

    // Disjoint decomposition: outside organizations, then quantities, then exact terms.
    var outsideOrganizations = new HashSet<>(organizations);
    outsideOrganizations.removeAll(covered.organizations);
    if (!outsideOrganizations.isEmpty()) {
      remaining.add(new MonthlyPriceCoverage(outsideOrganizations, quantities, conditions, annualUsage));
    }
    for (QuantityInterval interval : quantities.subtract(covered.quantities)) {
      remaining.add(new MonthlyPriceCoverage(covered.organizations, interval, conditions, annualUsage));
    }
    conditions.subtract(covered.conditions).ifPresent(outsideConditions -> remaining.add(
        new MonthlyPriceCoverage(covered.organizations, covered.quantities, outsideConditions, annualUsage)));
    for (var outsideAnnual : annualUsage.subtract(covered.annualUsage))
      remaining.add(new MonthlyPriceCoverage(covered.organizations, covered.quantities, covered.conditions, outsideAnnual));
    return List.copyOf(remaining);
  }

  public String canonical() {
    String orgs = MonthlyPriceCanonical.fields(
        "ORGANIZATIONS_V1", organizations.stream().sorted().toArray(String[]::new));
    String interval = MonthlyPriceCanonical.fields("QUANTITY_INTERVAL_V1",
        MonthlyPriceCanonical.number(quantities.lower()), Boolean.toString(quantities.lowerInclusive()),
        MonthlyPriceCanonical.number(quantities.upper()), Boolean.toString(quantities.upperInclusive()));
    return annualUsage.equals(AnnualUsageDomain.any())
        ? MonthlyPriceCanonical.fields("MONTHLY_PRICE_COVERAGE_V1", orgs, interval, conditions.canonical())
        : MonthlyPriceCanonical.fields("MONTHLY_PRICE_COVERAGE_V2", orgs, interval, conditions.canonical(), annualUsage.canonical());
  }
}
