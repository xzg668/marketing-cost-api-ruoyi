package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanUtils;

/** 层级查看器的只读子树投影，不改变来源层级或核算使用的 BOM。 */
final class BomHierarchySubtree {
  private BomHierarchySubtree() {}

  static List<BomRawHierarchy> extract(List<BomRawHierarchy> connected, BomRawHierarchy root) {
    String prefix = path(root.getPath());
    String rootPath = "/" + root.getMaterialCode() + "/";
    Map<String, BigDecimal> quantities = new HashMap<>();
    List<BomRawHierarchy> result = new ArrayList<>();
    // connected 已由有效树剪枝器按层级排序，父节点总在子节点之前。
    for (BomRawHierarchy row : connected) {
      String originalPath = path(row.getPath());
      if (!originalPath.startsWith(prefix)) {
        continue;
      }
      BomRawHierarchy copy = new BomRawHierarchy();
      BeanUtils.copyProperties(row, copy);
      copy.setTopProductCode(root.getMaterialCode());
      copy.setPath(rootPath + originalPath.substring(prefix.length()));
      copy.setLevel(row.getLevel() - root.getLevel());
      if (originalPath.equals(prefix)) {
        copy.setParentCode(root.getMaterialCode());
        copy.setQtyPerParent(BigDecimal.ONE);
        copy.setQtyPerTop(BigDecimal.ONE);
        // 原节点与其上级的标准/替代关系不属于以本件为根的下级 BOM。
        copy.setChildType(null);
        copy.setAlternativeGroupKey(null);
      } else {
        String parentPath = originalPath.substring(0, originalPath.lastIndexOf('/', originalPath.length() - 2) + 1);
        BigDecimal parentQuantity = quantities.get(parentPath);
        copy.setQtyPerTop(parentQuantity == null || row.getQtyPerParent() == null
            ? null : parentQuantity.multiply(row.getQtyPerParent()));
      }
      quantities.put(originalPath, copy.getQtyPerTop());
      result.add(copy);
    }
    return result;
  }

  static boolean sameBom(List<BomRawHierarchy> left, List<BomRawHierarchy> right) {
    return signature(left).equals(signature(right));
  }

  private static Map<String, NodeContent> signature(List<BomRawHierarchy> rows) {
    Map<String, NodeContent> result = new HashMap<>();
    for (BomRawHierarchy row : rows) {
      boolean root = row.getLevel() == 0;
      result.put(row.getPath(), new NodeContent(row.getMaterialCode(), row.getMaterialName(),
          row.getMaterialSpec(), row.getShapeAttr(), row.getSourceCategory(),
          decimal(row.getQtyPerParent()), row.getBomPurpose(),
          root ? null : row.getBomVersion(), root ? null : row.getSourceU9RowId(),
          row.getChildType(), row.getAlternativeGroupKey(),
          root ? null : row.getEffectiveFrom(), root ? null : row.getEffectiveTo()));
    }
    return result;
  }

  private static BigDecimal decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private static String path(String value) {
    return value.endsWith("/") ? value : value + "/";
  }

  private record NodeContent(String code, String name, String specification, String shape,
      String category, BigDecimal quantity, String purpose, String version, Long sourceRowId,
      String childType, String alternativeGroupKey, LocalDate from, LocalDate to) {}
}
