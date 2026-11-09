package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.util.*;

/** Displays the original adopted source and its unit, without selecting today's prices. */
final class JjbPricePresentation {
  private record Fact(Long id, String material, String unit, String source) {}
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;

  JjbPricePresentation(ObjectMapper json) {
    this.json = json;
    this.codec = new MonthlyPriceDocumentCodec(json);
  }

  ArrayList<Row> apply(List<Row> rows, MonthlyProductCost.Result calculation,
      List<PricePrepareItem> preparations) {
    var facts = new ArrayList<Fact>();
    if (calculation != null) for (var adopted : calculation.adoptedPrices()) {
      var family = adopted.request().family();
      facts.add(new Fact(adopted.selection().monthlyPriceId(), family.materialCode(), family.unit(),
          source(tree(adopted.selection().fact().evidence().json()).path("sourceKind").asText())));
    }
    var byPreparation = new HashMap<Long, Fact>();
    for (var prepared : preparations) {
      if (!"MONTHLY_PRICE".equals(prepared.getResultRefType())) continue;
      var captured = tree(prepared.getInputSnapshotJson());
      var family = captured.path("request").path("family");
      // Only ordinary preparation quantities use this identity conversion. MAKE raw-price
      // conversions describe a child price, not the parent quantity shown on the sheet.
      if (captured.path("unitPriceMultiplier").decimalValue().compareTo(java.math.BigDecimal.ONE) != 0) continue;
      var evidence = tree(captured.path("selection").path("fact").path("evidence").path("json").asText(null));
      if (Objects.equals(prepared.getMaterialCode(), family.path("materialCode").asText()))
        byPreparation.put(prepared.getId(), new Fact(prepared.getMonthlyPriceId(), prepared.getMaterialCode(),
            text(family, "unit"), source(text(evidence, "sourceKind"))));
    }
    var result = new ArrayList<Row>();
    for (var row : rows) {
      if (!Set.of("PART", "HOUSEHOLD", "EXPLANATION").contains(row.kind())) { result.add(row); continue; }
      var matching = facts.stream().filter(fact -> row.monthlyPriceIds().contains(fact.id())
          && Objects.equals(row.code(), fact.material())).toList();
      if (matching.isEmpty()) matching = row.sources().stream()
          .filter(ref -> "lp_price_prepare_item".equals(ref.table()))
          .map(ref -> byPreparation.get(ref.id())).filter(Objects::nonNull)
          .filter(fact -> Objects.equals(row.code(), fact.material())).toList();
      var units = matching.stream().map(Fact::unit).filter(Objects::nonNull).distinct().toList();
      var sources = matching.stream().map(Fact::source).filter(Objects::nonNull).distinct().toList();
      result.add(row.toBuilder().unit(units.size() == 1 ? units.getFirst() : row.unit())
          .priceSource(sources.isEmpty() ? source(row.priceSource()) : String.join("、", sources)).build());
    }
    return result;
  }

  static String source(String kind) {
    if (kind == null || kind.isBlank()) return null;
    return switch (kind) {
      case "LINKED", "LINKED_LEGACY", "LINKED_PRICE", "联动价格" -> "联动价";
      case "RANGE_FACTOR", "RANGE_LEGACY_FACTOR", "RANGE_QTY", "RANGE_PRICE" -> "区间价";
      case "PURCHASE_FIXED", "FIXED", "FIXED_PRICE", "固定采购价" -> "固定价";
      case "TECH_FIXED" -> "固定价（技术补录）";
      case "ODS_TIER", "TIER" -> "阶梯价";
      case "ODS_ORDER", "ORDER" -> "订单价";
      case "INQUIRY" -> "询价";
      case "HOUSEHOLD_INQUIRY" -> "询价（家用部件）";
      case "INVOICE" -> "发票价格";
      case "SETTLEMENT_FIXED", "SETTLE_FIXED" -> "结算固定价";
      case "SETTLEMENT_LINKED", "SETTLE_LINKED" -> "结算联动价";
      case "SCRAP_PRICE", "SCRAP" -> "废料价格";
      case "MONTHLY_PRICE", "月度物料价" -> "原始来源未记录";
      default -> kind;
    };
  }

  private JsonNode tree(String value) {
    if (value == null || value.isBlank()) return json.createObjectNode();
    try {
      var node = json.readTree(value);
      return node.has("document") ? json.readTree(codec.unwrap(value)) : node;
    } catch (java.io.IOException failure) { throw new IllegalStateException("保存的取价依据无法读取", failure); }
  }
  private static String text(JsonNode node, String field) {
    var value = node.path(field);
    return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
  }
}
