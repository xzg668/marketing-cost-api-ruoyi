package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationBomPricing;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class BomCostingRowAggregation {

  private BomCostingRowAggregation() {}

  static Result aggregate(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return new Result(List.of(), Map.of());
    }
    Map<Key, List<BomCostingRow>> groups = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (row != null) groups.computeIfAbsent(Key.of(row), key -> new ArrayList<>()).add(row);
    }
    List<BomCostingRow> aggregated = new ArrayList<>();
    Map<String, String> pathAliases = new LinkedHashMap<>();
    for (var group : groups.values()) {
      var first = group.getFirst();
      captureRecoveryBreakdown(group);
      aliasPath(pathAliases, first.getPath(), first.getPath());
      for (var row : group.subList(1, group.size())) {
        merge(first, row);
        aliasPath(pathAliases, row.getPath(), first.getPath());
      }
      aggregated.add(first);
    }
    return new Result(aggregated, pathAliases);
  }

  /** Keep the price/billing identity intact while retaining each recovery before adding quantities. */
  private static void captureRecoveryBreakdown(List<BomCostingRow> group) {
    if (group.size() < 2 || group.stream().noneMatch(BomCostingRowAggregation::outsourceScrap)
        || group.stream().anyMatch(row -> !"BYPRODUCT_EXTRA".equals(row.getSettlementRowType())
            || row.getQtyPerTop() == null || row.getQtyPerTop().signum() >= 0 || row.getPath() == null)) return;
    var lines = group.stream().map(row -> {
      var line = new LinkedHashMap<String, Object>();
      line.put("path", row.getPath());
      line.put("quantity", row.getQtyPerTop());
      line.put("name", outsourceScrap(row) || row.getParentCode() == null ? row.getMaterialName()
          : Objects.toString(row.getMaterialName(), row.getMaterialCode()) + "（" + row.getParentCode() + " 副产品）");
      line.put("drawingNo", row.getMaterialSpec());
      if (outsourceScrap(row)) line.put("outsource", row.getQuotationShapeEvidence());
      return line;
    }).toList();
    group.getFirst().setQuotationShapeEvidence(Map.of("kind", "RECOVERY_BREAKDOWN_V1", "lines", lines));
  }

  private static boolean outsourceScrap(BomCostingRow row) {
    return row.getQuotationShapeEvidence() != null
        && "OUTSOURCE_SCRAP_V1".equals(row.getQuotationShapeEvidence().get("kind"));
  }

  /**
   * 保留每个结构分支的计价行，只建立 path 到自身的引用映射。
   *
   * <p>电子图库见机表按部品分支展示，同一采购料号在不同部品下是不同的用量明细，
   * 不能按料号折成一行；正式 U9 链路仍使用 {@link #aggregate(List)} 的原有口径。
   */
  static Result preserveOccurrences(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return new Result(List.of(), Map.of());
    }
    List<BomCostingRow> preserved = new ArrayList<>();
    Map<String, String> pathAliases = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (row == null) {
        continue;
      }
      preserved.add(row);
      aliasPath(pathAliases, row.getPath(), row.getPath());
    }
    return new Result(preserved, pathAliases);
  }

  static String resolvePath(Map<String, String> pathAliases, String path) {
    if (path == null || pathAliases == null || pathAliases.isEmpty()) {
      return path;
    }
    return pathAliases.getOrDefault(path, path);
  }

  private static void merge(BomCostingRow target, BomCostingRow source) {
    target.setSupplementalPackaging(target.isSupplementalPackaging() || source.isSupplementalPackaging());
    if (target.getCurrentSubRefs() != null && source.getCurrentSubRefs() != null) {
      var refs = new ArrayList<>(target.getCurrentSubRefs());
      refs.addAll(source.getCurrentSubRefs());
      target.setCurrentSubRefs(refs);
    }
    target.setQtyPerParent(add(target.getQtyPerParent(), source.getQtyPerParent()));
    target.setQtyPerTop(add(target.getQtyPerTop(), source.getQtyPerTop()));
    target.setSubtreeCostRequired(maxFlag(target.getSubtreeCostRequired(), source.getSubtreeCostRequired()));
    target.setIsCostingRow(maxFlag(target.getIsCostingRow(), source.getIsCostingRow()));
    target.setManualModified(maxFlag(target.getManualModified(), source.getManualModified()));
    target.setLevel(minValue(target.getLevel(), source.getLevel()));
  }

  private static BigDecimal add(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.add(right);
  }

  private static Integer maxFlag(Integer left, Integer right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return Math.max(left, right);
  }

  private static Integer minValue(Integer left, Integer right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return Math.min(left, right);
  }

  private static void aliasPath(Map<String, String> pathAliases, String sourcePath, String targetPath) {
    if (sourcePath != null && targetPath != null) {
      pathAliases.put(sourcePath, targetPath);
    }
  }

  record Result(List<BomCostingRow> rows, Map<String, String> pathAliases) {}

  private record Key(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String materialCode,
      String periodMonth,
      java.time.LocalDate asOfDate,
      String householdOccurrence) {

    static Key of(BomCostingRow row) {
      return new Key(
          normalize(row.getOaNo()),
          row.getOaFormItemId(),
          normalize(row.getTopProductCode()),
          normalize(row.getMaterialCode()),
          normalize(row.getPeriodMonth()),
          row.getAsOfDate(),
          // 家用按原始路径截断子树；采用计划与来源引用不能被普通同料号聚合丢失。
          (MonthlyQuotationBomPricing.HOUSEHOLD_ROW.equals(row.getSettlementRowType())
              || !row.getHouseholdChildPaths().isEmpty())
              ? normalize(row.getPath()) : null);
    }

    private static String normalize(String value) {
      return value == null ? null : value.trim();
    }
  }
}
