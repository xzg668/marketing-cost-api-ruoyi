package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.costing.OrdinaryCostAmounts;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.util.*;

/** Reads explanation rows from immutable preparations using the existing MAKE/PACK validators. */
public final class MonthlyPreparationComponents {
  public record Component(String key, String materialCode, String materialName, String referenceType,
      Long referenceId, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
      List<Long> monthlyPriceIds, String traceJson) {}
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;

  public MonthlyPreparationComponents(ObjectMapper json, MonthlyPriceDocumentCodec codec) {
    this.json = json; this.codec = codec;
  }

  public Map<String, Component> read(PricePrepareItem parent) {
    var result = new LinkedHashMap<String, Component>();
    if ("MAKE_PART_PRICE".equals(parent.getResultRefType())) {
      var rows = Objects.requireNonNull(new MakePartCalculationEvidence(json).rows(parent), "Missing saved MAKE rows");
      for (var row : rows) {
        var ids = new ArrayList<Long>();
        ids.add(priceId(row.getRawInputSnapshotJson()));
        if (!Boolean.TRUE.equals(row.getNoScrapConfirmed())
            && row.getGrossWeightG().compareTo(row.getNetWeightG()) != 0)
          ids.add(priceId(row.getScrapInputSnapshotJson()));
        String key = row.getChildMaterialNo() + "|" + Objects.toString(row.getScrapCode(), "");
        put(result, new Component(key, row.getChildMaterialNo(), row.getChildMaterialName(),
            "MAKE_PART_COMPONENT", row.getId(), parent.getQuantity(), row.getCostPrice(),
            OrdinaryCostAmounts.money(parent.getQuantity().multiply(row.getCostPrice())),
            List.copyOf(ids), codec.unwrap(codec.encode(row))));
      }
    } else if (parent.getResultRefType() != null && parent.getResultRefType().startsWith("PACKAGE")) {
      var evidence = new PackageCalculationEvidence(json);
      var snapshot = Objects.requireNonNull(evidence.trace(parent), "Missing saved PACK rows");
      for (var entry : evidence.components(snapshot).entrySet()) {
        var row = entry.getValue();
        // These occurrences contribute independently as household rows in the saved version.
        if (evidence.household(row) != null || number(row, "quantity").signum() == 0) continue;
        var quantity = evidence.parentPrice(evidence.componentQuantity(row), snapshot);
        var unitContribution = evidence.parentPrice(number(row, "amount"), snapshot);
        put(result, new Component(entry.getKey(), row.path("materialCode").asText(),
            null, "PACKAGE_COMPONENT", row.hasNonNull("snapshotDetailId") ? row.path("snapshotDetailId").longValue() : null,
            quantity.multiply(parent.getQuantity()), number(row, "unitPrice"),
            OrdinaryCostAmounts.money(unitContribution.multiply(parent.getQuantity())),
            List.of(priceId(row.path("inputs").toString())), row.toString()));
      }
    }
    return Collections.unmodifiableMap(result);
  }

  private long priceId(String snapshot) {
    return Objects.requireNonNull(codec.decode(snapshot, MonthlyPreparationPrice.class).selection().monthlyPriceId(),
        "Component requires an adopted monthly price");
  }
  private static BigDecimal number(JsonNode value, String key) {
    if (!value.path(key).isNumber()) throw new IllegalArgumentException("Missing saved component number: " + key);
    return value.path(key).decimalValue();
  }
  private static void put(Map<String, Component> result, Component row) {
    if (result.putIfAbsent(row.key(), row) != null) throw new IllegalArgumentException("Duplicate saved component identity");
  }
}
