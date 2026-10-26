package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.util.*;

/** Cu dependency from the selected immutable source, including captured price dependencies. */
public final class MonthlyCopperExposure {
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyCopperExposure(MonthlyPriceDocumentCodec codec) { this.codec = codec; }

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
    var id = Objects.requireNonNull(selected.monthlyPriceId(), "Cu exposure requires adopted monthly price");
    if (result.containsKey(id)) return result.get(id);
    if (!visiting.add(request)) throw new IllegalArgumentException("Cyclic captured monthly dependency");
    var root = json(selected.fact().evidence().json());
    if (root.path("snapshotSchema").asInt() != 1)
      throw new IllegalArgumentException("Unknown price evidence version");
    var source = root.path("source");
    boolean copper = switch (root.path("sourceKind").asText()) {
      case "HOUSEHOLD_INQUIRY", "PURCHASE_FIXED", "TECH_FIXED", "RANGE_QTY", "SETTLE_FIXED", "ODS_ORDER", "ODS_TIER" -> false;
      case "LINKED" -> originalLinked(json(source.path("calculationJson").asText()));
      case "RANGE_FACTOR" -> isCopper(json(source.path("calculationJson").asText()).path("factorKey"));
      case "RANGE_LEGACY_FACTOR" -> isCopper(source.path("factorBinding").path("canonicalKey"));
      case "SETTLE_LINKED" -> source.path("rule").path("copperWeightG").decimalValue().signum() != 0;
      case "SCRAP_PRICE" -> scrap(source);
      case "LINKED_LEGACY" -> legacyLinked(source.path("calculation"));
      default -> throw new IllegalArgumentException("Unknown captured monthly source");
    };
    for (var dependency : selected.fact().dependencies())
      copper |= visit(dependency, prices, result, visiting);
    if (selected.fact().type() == MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY && copper)
      throw new IllegalArgumentException("Household monthly price cannot depend on Cu");
    visiting.remove(request); result.put(id, copper); return copper;
  }

  private boolean originalLinked(JsonNode calculation) {
    // Only factors actually consumed by the captured original calculation are relevant.
    var inputs = calculation.path("inputs").fieldNames();
    while (inputs.hasNext())
      if (isCopper(calculation.path("factorBindings").path(inputs.next()).path("canonicalKey"))) return true;
    return false;
  }

  private boolean legacyLinked(JsonNode calculation) {
    if (!"MONTHLY_LINKED_FORMULA_V1".equals(calculation.path("calculatorVersion").asText()))
      throw new IllegalArgumentException("Unknown captured formula version");
    // PreparedFormulaCalculationEvidence filters this context to getUsedFinanceKeys().
    // Looking at a whole financial publication would falsely mark unrelated constant formulas.
    if (calculation.path("context").path("financial").path("factors").has(PriceFactorContext.COPPER)) return true;
    for (var factor : calculation.path("factorKeys")) if (isCopper(factor)) return true;
    return false;
  }

  private boolean scrap(JsonNode source) {
    for (var candidate : source.path("candidates"))
      if (candidate.path("original").path("id").equals(source.path("selected").path("id"))) {
        for (var factor : candidate.path("input").path("rule").path("factorBindings"))
          if (isCopper(factor)) return true;
        return false;
      }
    throw new IllegalArgumentException("Missing selected scrap calculation evidence");
  }

  private boolean isCopper(JsonNode key) { return PriceFactorContext.COPPER.equals(key.asText()); }
  private JsonNode json(String value) { return codec.decode(codec.wrap(value), JsonNode.class); }
}
