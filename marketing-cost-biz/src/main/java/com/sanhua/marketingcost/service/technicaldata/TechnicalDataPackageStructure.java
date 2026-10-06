package com.sanhua.marketingcost.service.technicaldata;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 同一包装料号可在树中出现多次，完整性必须按节点路径逐一检查。 */
public final class TechnicalDataPackageStructure {
  private TechnicalDataPackageStructure() {}

  public record Node(String code, String parentCode, String path, Integer level, String priceOrg) {}

  public static boolean immediateChild(Node parent, Node child) {
    if (parent.path() == null || child.path() == null || parent.level() == null || child.level() == null) return false;
    int end = child.path().lastIndexOf('/', child.path().length() - 2);
    return child.level() == parent.level() + 1 && Objects.equals(child.parentCode(), parent.code())
        && end >= 0 && child.path().substring(0, end + 1).equals(parent.path())
        && Objects.equals(parent.priceOrg(), child.priceOrg());
  }

  public static List<Node> missingParents(List<Node> nodes, Set<String> packageCodes) {
    return nodes.stream().filter(parent -> packageCodes.contains(parent.code()))
        .filter(parent -> nodes.stream().noneMatch(child -> immediateChild(parent, child))).toList();
  }
}
