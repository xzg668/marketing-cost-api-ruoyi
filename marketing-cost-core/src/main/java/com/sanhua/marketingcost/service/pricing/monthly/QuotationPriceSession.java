package com.sanhua.marketingcost.service.pricing.monthly;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/** Calculation-local price inputs shared by normal, manufacturing and packaging consumers. */
public interface QuotationPriceSession {
  /** Shared only within one explicit preparation context; never a thread-local or a global cache. */
  final class Scope {
    private QuotationPriceSession session;
    public QuotationPriceSession get() { return session; }
    public QuotationPriceSession initialize(java.util.function.Supplier<QuotationPriceSession> factory) {
      if (session == null) session = java.util.Objects.requireNonNull(factory.get());
      return session;
    }
  }

  enum Purpose { MATERIAL, SCRAP }

  /** Null tax retains the existing raw source amount; no implicit tax conversion is allowed. */
  record Input(String materialCode, String organization, String unit, String specification,
      TaxBasis taxBasis, String supplier, NonCuConditions conditions, BigDecimal basisQuantity,
      Purpose purpose) {}

  record Value(MonthlyPriceRequest request, MonthlyAdoptionPlan.Selection selection,
      BigDecimal financialPrice, BigDecimal customerPrice) {}

  record Read(Map<Input, Value> values, Map<Input, MonthlyMaterialPrices.Problem> problems) {
    public Read { values = Map.copyOf(values); problems = Map.copyOf(problems); }
  }

  Read read(Collection<Input> inputs);

  MonthlyMaterialPrices.Context context();

  NonCuConditions conditions(String organization);

  /** Bind the original BOM's selected pricing paths to this preparation attempt. */
  void bindScope(MonthlyBomPricingScope.Scope scope);

  /** The immutable original-tree selection for this attempt; never inferred from old settlement IDs. */
  default java.util.Optional<MonthlyBomPricingScope.Scope> bomScope() {
    return java.util.Optional.empty();
  }

  /** Record a priced occurrence only after the existing consumer accepts its calculation input. */
  void consume(String path, Input input, BigDecimal effectiveQuantity);

  /** Restores a base input captured by an existing aggregate calculator. */
  void consume(String path, Value value, BigDecimal effectiveQuantity);

  MonthlyAdoptionPlan plan();
}
