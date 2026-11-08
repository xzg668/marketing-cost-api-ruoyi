package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import java.util.*;

/** Material base-price dependencies from the selected source, including captured child prices. */
public final class MonthlyMetalExposure {
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyMetalExposure(MonthlyPriceDocumentCodec codec) { this.codec = codec; }

  public Map<Long, Boolean> read(Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> prices) {
    var result = new LinkedHashMap<Long, Boolean>();
    var visiting = new HashSet<MonthlyPriceRequest>();
    for (var request : prices.keySet()) visit(request, prices, result, visiting);
    return Map.copyOf(result);
  }

  private boolean visit(MonthlyPriceRequest request,
      Map<MonthlyPriceRequest, MonthlyAdoptionPlan.Selection> prices, Map<Long, Boolean> result,
      Set<MonthlyPriceRequest> visiting) {
    var selected = Objects.requireNonNull(prices.get(request), "Missing captured monthly dependency");
    var id = Objects.requireNonNull(selected.monthlyPriceId(), "Metal exposure requires adopted monthly price");
    if (result.containsKey(id)) return result.get(id);
    if (!visiting.add(request)) throw new IllegalArgumentException("Cyclic captured monthly dependency");
    var root = json(selected.fact().evidence().json());
    if (root.path("snapshotSchema").asInt() != 1)
      throw new IllegalArgumentException("Unknown price evidence version");
    var source = root.path("source");
    boolean affected = switch (root.path("sourceKind").asText()) {
      case "HOUSEHOLD_INQUIRY" -> root.path("metalInputs").path("copperGrams").decimalValue().signum()!=0 || root.path("metalInputs").path("zincGrams").decimalValue().signum()!=0;
      case "PURCHASE_FIXED", "TECH_FIXED", "RANGE_QTY", "SETTLE_FIXED", "ODS_ORDER", "ODS_TIER", "INVOICE" -> false;
      case "LINKED" -> originalLinked(json(source.path("calculationJson").asText()));
      case "INQUIRY" -> hasMetal(source.path("selected").path("calculation").path("financeFactors"));
      case "RANGE_FACTOR" -> isMetalKey(json(source.path("calculationJson").asText()).path("factorKey"));
      case "RANGE_LEGACY_FACTOR" -> isMetalKey(source.path("factorBinding").path("canonicalKey"));
      case "SETTLE_LINKED" -> source.path("rule").path("copperWeightG").decimalValue().signum() != 0
          || source.path("rule").path("zincWeightG").decimalValue().signum() != 0;
      case "SCRAP_PRICE" -> scrap(source);
      case "LINKED_LEGACY" -> legacyLinked(source.path("calculation"));
      default -> throw new IllegalArgumentException("Unknown captured monthly source");
    };
    for (var dependency : selected.fact().dependencies())
      affected |= visit(dependency, prices, result, visiting);
    visiting.remove(request); result.put(id, affected); return affected;
  }

  private boolean originalLinked(JsonNode calculation) {
    // Only factors actually consumed by the captured original calculation are relevant.
    var inputs = calculation.path("inputs").fieldNames();
    while (inputs.hasNext())
      if (isMetalKey(calculation.path("factorBindings").path(inputs.next()).path("canonicalKey"))) return true;
    return false;
  }

  private boolean legacyLinked(JsonNode calculation) {
    if (!"MONTHLY_LINKED_FORMULA_V1".equals(calculation.path("calculatorVersion").asText()))
      throw new IllegalArgumentException("Unknown captured formula version");
    // PreparedFormulaCalculationEvidence filters this context to getUsedFinanceKeys().
    // Looking at a whole financial publication would falsely mark unrelated constant formulas.
    if (hasMetal(calculation.path("context").path("financial").path("factors"))) return true;
    for (var factor : calculation.path("factorKeys")) if (isMetalKey(factor)) return true;
    return false;
  }

  private boolean scrap(JsonNode source) {
    for (var candidate : source.path("candidates"))
      if (candidate.path("original").path("id").equals(source.path("selected").path("id"))) {
        for (var factor : candidate.path("input").path("rule").path("factorBindings"))
          if (isMetalKey(factor)) return true;
        return false;
      }
    throw new IllegalArgumentException("Missing selected scrap calculation evidence");
  }

  private boolean hasMetal(JsonNode factors) {
    var keys = factors.fieldNames();
    while (keys.hasNext()) if (isMetal(keys.next())) return true;
    return false;
  }
  private boolean isMetalKey(JsonNode key) { return isMetal(key.asText()); }
  private boolean isMetal(String key) {
    return CustomerMaterialPrices.affects(key);
  }
  private JsonNode json(String value) { return codec.decode(codec.wrap(value), JsonNode.class); }
}
