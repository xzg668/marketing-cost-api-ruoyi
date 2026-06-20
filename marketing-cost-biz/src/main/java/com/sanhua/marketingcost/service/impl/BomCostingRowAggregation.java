package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BomCostingRowAggregation {

  private BomCostingRowAggregation() {}

  static Result aggregate(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return new Result(List.of(), Map.of());
    }
    Map<Key, BomCostingRow> rowByKey = new LinkedHashMap<>();
    Map<String, String> pathAliases = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (row == null) {
        continue;
      }
      Key key = Key.of(row);
      BomCostingRow existing = rowByKey.get(key);
      if (existing == null) {
        rowByKey.put(key, row);
        aliasPath(pathAliases, row.getPath(), row.getPath());
        continue;
      }
      merge(existing, row);
      aliasPath(pathAliases, row.getPath(), existing.getPath());
    }
    return new Result(new ArrayList<>(rowByKey.values()), pathAliases);
  }

  static String resolvePath(Map<String, String> pathAliases, String path) {
    if (path == null || pathAliases == null || pathAliases.isEmpty()) {
      return path;
    }
    return pathAliases.getOrDefault(path, path);
  }

  private static void merge(BomCostingRow target, BomCostingRow source) {
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
      java.time.LocalDate asOfDate) {

    static Key of(BomCostingRow row) {
      return new Key(
          normalize(row.getOaNo()),
          row.getOaFormItemId(),
          normalize(row.getTopProductCode()),
          normalize(row.getMaterialCode()),
          normalize(row.getPeriodMonth()),
          row.getAsOfDate());
    }

    private static String normalize(String value) {
      return value == null ? null : value.trim();
    }
  }
}
