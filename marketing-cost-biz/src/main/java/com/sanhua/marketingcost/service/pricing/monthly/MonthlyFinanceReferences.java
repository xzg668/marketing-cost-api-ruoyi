package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/** Financial publications actually used by a saved source, including supplier alternatives. */
public final class MonthlyFinanceReferences {
  public record Reference(String organization, long batchId) {}

  private MonthlyFinanceReferences() {}

  public static Set<Reference> read(JsonNode document) {
    var result = new LinkedHashSet<Reference>();
    var source = document.path("source");
    switch (document.path("sourceKind").asText()) {
      case "HOUSEHOLD_INQUIRY" -> financial(result,document.path("metalInputs").path("financial").path("financial"));
      case "LINKED", "RANGE_FACTOR" -> {
        publication(result, source.path("selectedPublication"));
        source.path("publications").forEach(value -> publication(result, value));
      }
      case "LINKED_LEGACY" -> financial(result, source.path("calculation").path("context").path("financial"));
      case "RANGE_LEGACY_FACTOR" -> financial(result, source.path("financialInputs").path("financial"));
      case "SCRAP_PRICE" -> source.path("candidates").forEach(value ->
          financial(result, value.path("input").path("factors").path("financial")));
      case "SETTLE_LINKED" -> financial(result, source.path("base").path("financial"));
      case "INQUIRY" -> {
        inquiry(result, source.path("selected"));
        source.path("candidates").forEach(value -> inquiry(result, value));
      }
      default -> { /* Fixed purchases and invoices do not use financial bases. */ }
    }
    return Set.copyOf(result);
  }

  private static void financial(Set<Reference> result, JsonNode value) {
    add(result, value.path("scope").path("priceOrgCode").asText(), value.path("batchId").asLong());
  }

  private static void publication(Set<Reference> result, JsonNode value) {
    add(result, value.path("orgCode").asText(), value.path("financeBatchId").asLong());
  }

  private static void inquiry(Set<Reference> result, JsonNode value) {
    add(result, "210", value.path("calculation").path("financeBatchId").asLong());
  }

  private static void add(Set<Reference> result, String organization, long batchId) {
    if (batchId <= 0) return;
    if (!Set.of("210", "220").contains(organization))
      throw new IllegalArgumentException("月价财务依据的组织无效");
    result.add(new Reference(organization, batchId));
  }
}
