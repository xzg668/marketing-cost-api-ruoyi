package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Expands saved recovery contributions for the sheet only, leaving the saved calculation intact. */
final class JjbRecoveryPresentation {
  private record Recovery(String path, String name, String drawingNo, BigDecimal quantity,
      Map<String, Object> outsource) {}
  private record Reconciliation(Long versionId, String versionNo, Long itemId, Long preparationId,
      String materialCode, String mergedPath, List<Recovery> lines) {}

  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final List<Reconciliation> reconciliations;

  JjbRecoveryPresentation(ObjectMapper json) {
    this.json = json;
    this.codec = new MonthlyPriceDocumentCodec(json);
    try (var input = JjbRecoveryPresentation.class.getResourceAsStream("/jjb-recovery-reconciliations.json")) {
      reconciliations = input == null ? List.of() : json.readValue(input, new TypeReference<>() {});
    } catch (IOException error) {
      throw new IllegalStateException("废料回收核对依据无法读取", error);
    }
  }

  List<Row> apply(QuoteCostRunVersion version, List<Row> rows, List<PricePrepareItem> preparations) {
    var byId = preparations.stream().collect(Collectors.toMap(PricePrepareItem::getId, Function.identity()));
    var result = new ArrayList<Row>();
    for (var row : rows) {
      var preparation = row.sources().stream().filter(source -> "lp_price_prepare_item".equals(source.table()))
          .map(source -> byId.get(source.id())).filter(Objects::nonNull).findFirst().orElse(null);
      if (preparation == null || !"PART".equals(row.kind()) || !"ORDINARY_PARTS".equals(row.section())
          || !"副产品".equals(row.shape()) || row.amount() == null || row.quantity() == null) {
        result.add(row);
        continue;
      }
      var breakdown = savedBreakdown(preparation);
      if (breakdown.isEmpty()) breakdown = reconciliations.stream()
          .filter(saved -> Objects.equals(saved.versionId(), version.getId())
              && Objects.equals(saved.versionNo(), version.getVersionNo())
              && Objects.equals(saved.itemId(), version.getOaFormItemId())
              && Objects.equals(saved.preparationId(), preparation.getId())
              && Objects.equals(saved.materialCode(), row.code()) && row.paths().contains(saved.mergedPath()))
          .findFirst().map(Reconciliation::lines).orElse(List.of());
      result.addAll(split(row, preparation.getUnitPrice(), breakdown));
    }
    return result;
  }

  private List<Recovery> savedBreakdown(PricePrepareItem preparation) {
    String source = preparation.getInputSnapshotJson();
    if (source == null || !MonthlyPreparationPrice.KIND.equals(preparation.getResultRefType())) return List.of();
    try {
      JsonNode snapshot = json.readTree(codec.unwrap(source));
      var evidence = snapshot.path("materialShapeEvidence");
      if (!"RECOVERY_BREAKDOWN_V1".equals(evidence.path("kind").asText()) || !evidence.path("lines").isArray())
        return List.of();
      return json.convertValue(evidence.path("lines"), new TypeReference<>() {});
    } catch (IOException | IllegalArgumentException invalid) {
      return List.of();
    }
  }

  private static List<Row> split(Row row, BigDecimal price, List<Recovery> breakdown) {
    if (price == null || price.signum() < 0 || breakdown.size() < 2) return List.of(row);
    var paths = new HashSet<String>();
    BigDecimal quantity = BigDecimal.ZERO;
    for (var line : breakdown) {
      if (line.path() == null || line.path().isBlank() || line.quantity() == null || line.quantity().signum() >= 0
          || !paths.add(MonthlyBomPricingScope.pathKey(line.path()))) return List.of(row);
      quantity = quantity.add(line.quantity());
    }
    if (quantity.compareTo(new BigDecimal(row.quantity())) != 0) return List.of(row);

    BigDecimal remaining = new BigDecimal(row.amount());
    int amountScale = remaining.scale();
    var split = new ArrayList<Row>();
    for (int i = 0; i < breakdown.size(); i++) {
      var line = breakdown.get(i);
      // Allocate the display rounding to the last contribution so the saved line amount is conserved.
      BigDecimal amount = i == breakdown.size() - 1 ? remaining
          : line.quantity().multiply(price).setScale(amountScale, RoundingMode.HALF_UP).max(remaining);
      remaining = remaining.subtract(amount);
      var displayed = row.toBuilder().key(row.key() + ":RECOVERY:" + i)
          .name(line.name() == null || line.name().isBlank() ? row.name() : line.name())
          .displayName(null).drawingNo(line.drawingNo()).quantity(JjbProjection.decimal(line.quantity()))
          .amount(amount.toPlainString()).paths(List.of(MonthlyBomPricingScope.pathKey(line.path())));
      if (line.outsource() != null) {
        var evidence = line.outsource();
        String parent = Objects.toString(evidence.get("parentMaterialCode"), "");
        String material = Objects.toString(evidence.get("rawMaterialCode"), "");
        displayed.name(Objects.toString(evidence.get("parentMaterialName"), "") + " " + parent
                + " · 委外加工废料（毛坯 " + material + "）")
            .grossWeight(Objects.toString(evidence.get("grossWeightG"), null))
            .netWeight(Objects.toString(evidence.get("netWeightG"), null))
            .scrapUnitPrice(row.unitPrice()).remark("毛坯 " + material + " 毛重减母件理论净重");
      }
      split.add(displayed.build());
    }
    return split;
  }
}
