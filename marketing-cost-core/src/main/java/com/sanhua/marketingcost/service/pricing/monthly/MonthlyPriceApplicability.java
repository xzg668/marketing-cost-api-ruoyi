package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The complete immutable applicability of one adopted price, possibly split into fragments. */
public record MonthlyPriceApplicability(List<MonthlyPriceCoverage> fragments,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    String specialPolicyRevision) {

  public MonthlyPriceApplicability(List<MonthlyPriceCoverage> fragments) { this(fragments,null); }

  public MonthlyPriceApplicability {
    if(specialPolicyRevision!=null&&!specialPolicyRevision.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid special price policy revision");
    fragments = List.copyOf(fragments).stream().distinct()
        .sorted(Comparator.comparing(MonthlyPriceCoverage::canonical)).toList();
    if (fragments.isEmpty()) {
      throw new IllegalArgumentException("An adopted price must have nonempty applicability");
    }
    for (int i = 0; i < fragments.size(); i++) {
      for (int j = i + 1; j < fragments.size(); j++) {
        if (fragments.get(i).intersection(fragments.get(j)).isPresent()) {
          throw new IllegalArgumentException("Applicability fragments must not overlap");
        }
      }
    }
  }

  public static MonthlyPriceApplicability of(MonthlyPriceCoverage coverage) {
    return new MonthlyPriceApplicability(List.of(coverage));
  }

  public boolean matches(MonthlyPriceRequest request) {
    Objects.requireNonNull(request, "Monthly price request is required");
    return fragments.stream().anyMatch(fragment -> fragment.matches(request));
  }

  public String canonical() {
    if(specialPolicyRevision!=null) {
      var fields=new ArrayList<String>();fields.add(specialPolicyRevision);
      fragments.stream().map(MonthlyPriceCoverage::canonical).forEach(fields::add);
      return MonthlyPriceCanonical.fields("MONTHLY_PRICE_APPLICABILITY_POLICY_V1",fields.toArray(String[]::new));
    }
    return MonthlyPriceCanonical.fields("MONTHLY_PRICE_APPLICABILITY_V1",
        fragments.stream().map(MonthlyPriceCoverage::canonical).toArray(String[]::new));
  }

  public Optional<MonthlyPriceApplicability> subtract(MonthlyPriceApplicability other) {
    Objects.requireNonNull(other, "Other price applicability is required");
    if(!Objects.equals(specialPolicyRevision,other.specialPolicyRevision))return Optional.of(this);
    List<MonthlyPriceCoverage> remaining = fragments;
    for (MonthlyPriceCoverage occupied : other.fragments) {
      var next = new ArrayList<MonthlyPriceCoverage>();
      for (MonthlyPriceCoverage fragment : remaining) next.addAll(fragment.subtract(occupied));
      if (next.isEmpty()) return Optional.empty();
      remaining = next;
    }
    return Optional.of(new MonthlyPriceApplicability(remaining,specialPolicyRevision));
  }
}
