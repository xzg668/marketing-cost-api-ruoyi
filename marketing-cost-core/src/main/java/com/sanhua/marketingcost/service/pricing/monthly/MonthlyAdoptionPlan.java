package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure adoption input; request entries are lookup conditions, not database unique keys. */
public record MonthlyAdoptionPlan(
    Map<MonthlyPriceRequest, Selection> selections,
    List<Use> uses,
    Set<String> suppressedPaths,
    Set<MonthlyPriceRequest> ordinaryScopeRequests) {

  public MonthlyAdoptionPlan(Map<MonthlyPriceRequest, Selection> selections, List<Use> uses,
      Set<String> suppressedPaths) {
    this(selections, uses, suppressedPaths, Set.of());
  }

  public MonthlyAdoptionPlan {
    selections = Map.copyOf(selections);
    uses = List.copyOf(uses);
    suppressedPaths = Set.copyOf(suppressedPaths);
    // Includes unpriced structure/filter nodes whose adoption as HOUSEHOLD would change this tree.
    // These are read/lock observations only; they must never create placeholder monthly prices.
    ordinaryScopeRequests = Set.copyOf(ordinaryScopeRequests);
    if (uses.isEmpty() && !suppressedPaths.isEmpty()) {
      throw new IllegalArgumentException("Suppressed paths require an effective household use");
    }
  }

  public record Selection(
      Long monthlyPriceId, MonthlyPriceApplicability applicability, PriceFact fact) {
    public Selection {
      Objects.requireNonNull(applicability, "applicability");
      Objects.requireNonNull(fact, "fact");
    }

    public Selection(Long monthlyPriceId, MonthlyPriceCoverage coverage, PriceFact fact) {
      this(monthlyPriceId, MonthlyPriceApplicability.of(coverage), fact);
    }
  }

  public record Use(
      String path, MonthlyPriceRequest request, BigDecimal cumulativeQuantity) {
    public Use {
      if (path == null || path.isBlank()) throw new IllegalArgumentException("Use path is required");
      path = path.trim();
      Objects.requireNonNull(request, "request");
      if (cumulativeQuantity == null || cumulativeQuantity.signum() <= 0) {
        throw new IllegalArgumentException("Effective use quantity must be positive");
      }
      cumulativeQuantity = cumulativeQuantity.stripTrailingZeros();
    }
  }

  public enum PricingType {
    NORMAL,
    HOUSEHOLD_INQUIRY
  }

  public record PriceFact(
      PricingType type,
      BigDecimal price,
      BigDecimal householdRate,
      SourceEvidence evidence,
      Set<MonthlyPriceRequest> dependencies) {

    public PriceFact {
      Objects.requireNonNull(type, "type");
      price = type == PricingType.NORMAL
          ? exactNonnegative(price, 38, 18, "ordinary price")
          : exactNonnegative(price, 24, 12, "household price");
      Objects.requireNonNull(evidence, "evidence");
      dependencies = Set.copyOf(dependencies);
      if (type == PricingType.NORMAL && householdRate != null) {
        throw new IllegalArgumentException("Ordinary facts cannot carry a household rate");
      }
      if (type == PricingType.HOUSEHOLD_INQUIRY) {
        householdRate = exactNonnegative(householdRate, 18, 12, "household rate");
        if (!dependencies.isEmpty()) {
          throw new IllegalArgumentException("Household facts cannot depend on included child prices");
        }
      }
    }

    public static PriceFact normal(
        BigDecimal price, SourceEvidence evidence, Set<MonthlyPriceRequest> dependencies) {
      return new PriceFact(PricingType.NORMAL, price, null, evidence, dependencies);
    }

    public static PriceFact household(
        BigDecimal price, BigDecimal householdRate, SourceEvidence evidence) {
      return new PriceFact(
          PricingType.HOUSEHOLD_INQUIRY, price, householdRate, evidence, Set.of());
    }
  }

  private static BigDecimal exactNonnegative(
      BigDecimal value, int precision, int scale, String field) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(field + " must be nonnegative and present");
    }
    BigDecimal normalized = value.stripTrailingZeros();
    if (Math.max(0, normalized.scale()) > scale
        || (long) normalized.precision() - normalized.scale() > precision - scale) {
      throw new IllegalArgumentException(field + " exceeds its exact storage precision");
    }
    return normalized;
  }

  public record SourceEvidence(String json) {
    public SourceEvidence {
      json = ExactJsonDocument.canonicalObject(json, 1024 * 1024);
    }

    public static SourceEvidence ofJson(String json) {
      return new SourceEvidence(json);
    }

    public String sha256() {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("The JVM must provide SHA-256", impossible);
      }
    }

  }
}
