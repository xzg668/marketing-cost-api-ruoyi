package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.util.StringUtils;

/** 按裸品自身料号取同组织 U9 主制造子树，每个来源整树和构建批次分别核实。 */
public final class BareProductBomSubtree {
  private BareProductBomSubtree() {}

  public static List<BomRawHierarchy> read(BomRawHierarchyMapper mapper, String material,
      String priceOrg, LocalDate date) {
    var matches = mapper.selectList(scope(priceOrg, date)
        .eq(BomRawHierarchy::getMaterialCode, material)
        .gt(BomRawHierarchy::getLevel, 0)
        .orderByAsc(BomRawHierarchy::getTopProductCode, BomRawHierarchy::getPath, BomRawHierarchy::getId));
    var sources = matches.stream().map(r -> new Source(r.getTopProductCode(), r.getBuildBatchId()))
        .distinct().toList();
    List<BomRawHierarchy> selected = List.of();
    for (var source : sources) {
      if (!StringUtils.hasText(source.batch())) {
        throw new IllegalStateException("裸品 " + material + " 的 U9 层级缺少构建批次");
      }
      var connected = connected(mapper, priceOrg, date, source);
      for (var node : connected) {
        if (!material.equals(node.getMaterialCode()) || node.getLevel() == 0) continue;
        var candidate = BomHierarchySubtree.extract(connected, node);
        if (!selected.isEmpty() && !BomHierarchySubtree.sameBom(selected, candidate)) {
          throw new IllegalStateException("裸品 " + material + " 存在不同的有效主制造 BOM，请核实来源");
        }
        if (selected.isEmpty()) selected = candidate;
      }
    }
    return normalizeRoot(selected);
  }

  public static List<BomRawHierarchy> readVerifiedRoot(BomRawHierarchyMapper mapper, String material,
      String priceOrg, LocalDate date, String batch, Long rootId) {
    var root = mapper.selectById(rootId);
    if (root == null || !material.equals(root.getMaterialCode()) || !batch.equals(root.getBuildBatchId())
        || !priceOrg.equals(root.getPriceOrgCode())) return List.of();
    var rows = connected(mapper, priceOrg, date, new Source(root.getTopProductCode(), batch));
    return rows.stream().filter(row -> rootId.equals(row.getId())).findFirst()
        .map(node -> normalizeRoot(BomHierarchySubtree.extract(rows, node))).orElse(List.of());
  }

  private static List<BomRawHierarchy> connected(BomRawHierarchyMapper mapper, String org,
      LocalDate date, Source source) {
    var rows = mapper.selectList(scope(org, date)
        .eq(BomRawHierarchy::getTopProductCode, source.top())
        .eq(BomRawHierarchy::getBuildBatchId, source.batch()));
    return BomEffectiveTreePruner.prune(rows, source.top());
  }

  private static List<BomRawHierarchy> normalizeRoot(List<BomRawHierarchy> selected) {
    if (selected.size() < 2) return List.of();
    // 根原先是成品的子件。它与成品之间的行号、工序、版本均不属于裸品自身 BOM。
    var root = selected.getFirst();
    root.setSourceU9RowId(null);
    root.setSourceLineKey(null);
    root.setProcessSeq(null);
    root.setBomVersion(null);
    return selected;
  }

  private static LambdaQueryWrapper<BomRawHierarchy> scope(String priceOrg, LocalDate date) {
    return Wrappers.<BomRawHierarchy>lambdaQuery()
        .eq(BomRawHierarchy::getPriceOrgCode, priceOrg)
        .eq(BomRawHierarchy::getSourceType, "U9")
        .eq(BomRawHierarchy::getBomPurpose, "主制造")
        .and(q -> q.eq(BomRawHierarchy::getLevel, 0).or().eq(BomRawHierarchy::getBomStatus, "已核准"))
        .le(BomRawHierarchy::getEffectiveFrom, date)
        .and(q -> q.isNull(BomRawHierarchy::getEffectiveTo).or().ge(BomRawHierarchy::getEffectiveTo, date));
  }

  private record Source(String top, String batch) {}
}
