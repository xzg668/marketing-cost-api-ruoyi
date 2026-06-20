package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * BOM 有效树剪枝。
 *
 * <p>raw_hierarchy 保留 U9 多版本行；消费给报价核算时，调用方先按 asOfDate / period
 * 过滤生失效日期，再用本类收敛成一棵连通有效树：
 * <ul>
 *   <li>同一路径出现多个有效版本时，取 effective_from 最新的版本；</li>
 *   <li>非顶层节点只有父路径也有效时才保留，避免跨版本拼出来的孤儿子树进入结算。</li>
 * </ul>
 */
final class BomEffectiveTreePruner {

  private BomEffectiveTreePruner() {}

  static List<BomRawHierarchy> prune(List<BomRawHierarchy> rows, String topProductCode) {
    String top = trimToNull(topProductCode);
    if (top == null || rows == null || rows.isEmpty()) {
      return List.of();
    }

    Map<String, BomRawHierarchy> bestByPath = new LinkedHashMap<>();
    for (BomRawHierarchy row : rows) {
      String path = normalizePath(row == null ? null : row.getPath());
      if (path == null) {
        continue;
      }
      bestByPath.merge(path, row, BomEffectiveTreePruner::newerVersion);
    }
    if (bestByPath.isEmpty()) {
      return List.of();
    }

    List<BomRawHierarchy> sorted = new ArrayList<>(bestByPath.values());
    sorted.sort(rowComparator());

    Set<String> keptPaths = new LinkedHashSet<>();
    List<BomRawHierarchy> kept = new ArrayList<>();
    String topPath = "/" + top + "/";
    for (BomRawHierarchy row : sorted) {
      String path = normalizePath(row.getPath());
      if (path == null) {
        continue;
      }
      if (isTopRow(row, top, topPath)) {
        keptPaths.add(path);
        kept.add(row);
        continue;
      }
      String parentPath = parentPath(path);
      if (parentPath != null && keptPaths.contains(parentPath)) {
        keptPaths.add(path);
        kept.add(row);
      }
    }
    return kept;
  }

  private static BomRawHierarchy newerVersion(BomRawHierarchy left, BomRawHierarchy right) {
    int dateCompare = nullAsMin(left.getEffectiveFrom()).compareTo(nullAsMin(right.getEffectiveFrom()));
    if (dateCompare < 0) {
      return right;
    }
    if (dateCompare > 0) {
      return left;
    }
    Long leftId = left.getId() == null ? Long.MIN_VALUE : left.getId();
    Long rightId = right.getId() == null ? Long.MIN_VALUE : right.getId();
    return rightId > leftId ? right : left;
  }

  private static Comparator<BomRawHierarchy> rowComparator() {
    return Comparator
        .comparing((BomRawHierarchy row) -> row.getLevel() == null ? Integer.MAX_VALUE : row.getLevel())
        .thenComparing(row -> normalizePath(row.getPath()) == null ? "" : normalizePath(row.getPath()))
        .thenComparing(row -> row.getSortSeq() == null ? Integer.MAX_VALUE : row.getSortSeq())
        .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId());
  }

  private static boolean isTopRow(BomRawHierarchy row, String top, String topPath) {
    return top.equals(trimToNull(row.getTopProductCode()))
        && top.equals(trimToNull(row.getMaterialCode()))
        && topPath.equals(normalizePath(row.getPath()));
  }

  private static String parentPath(String path) {
    String normalized = normalizePath(path);
    if (normalized == null) {
      return null;
    }
    int lastSlashBeforeTail = normalized.lastIndexOf('/', normalized.length() - 2);
    if (lastSlashBeforeTail <= 0) {
      return null;
    }
    return normalized.substring(0, lastSlashBeforeTail + 1);
  }

  private static String normalizePath(String path) {
    String value = trimToNull(path);
    if (value == null) {
      return null;
    }
    return value.endsWith("/") ? value : value + "/";
  }

  private static LocalDate nullAsMin(LocalDate value) {
    return value == null ? LocalDate.MIN : value;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
