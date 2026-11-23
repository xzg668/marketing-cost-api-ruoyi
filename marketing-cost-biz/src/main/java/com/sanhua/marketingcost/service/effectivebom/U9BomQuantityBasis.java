package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.bom.U9BomLineKey;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.bomalternative.U9BomAlternativeMetadata;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Converts U9 batch usage to usage per direct parent before accumulating quantities. */
@Component
public class U9BomQuantityBasis {
  private static final int SOURCE_BATCH_SIZE = 128;
  private static final int QUANTITY_SCALE = 16;

  private final BomU9SourceMapper sources;
  private final BomRawHierarchyMapper hierarchy;
  private final PackageComponentIdentifyService packages;

  public U9BomQuantityBasis(BomU9SourceMapper sources, BomRawHierarchyMapper hierarchy,
      PackageComponentIdentifyService packages) {
    this.sources = sources;
    this.hierarchy = hierarchy;
    this.packages = packages;
  }

  public List<BomRawHierarchy> normalize(List<BomRawHierarchy> rows) {
    var bases = parentBases(rows, false);
    if (bases.isEmpty()) return rows;
    return rows.stream().map(row -> {
      var base = bases.get(row.getPath());
      if (base == null) return row;
      var copy = copy(row);
      copy.setQtyPerParent(row.getQtyPerParent().divide(base, QUANTITY_SCALE, RoundingMode.HALF_UP));
      copy.setFrozenU9ParentBaseQty(null);
      return copy;
    }).toList();
  }

  /** Included for affected trees even after the corrected effective quantities have been saved. */
  public String revision(List<QuoteEffectiveBomNode> nodes, Function<Long, List<BomRawHierarchy>> snapshotRows) {
    var u9Nodes = nodes.stream().filter(node -> "U9".equals(node.getSourceBomType())
        && node.getNodeLevel() != null && node.getNodeLevel() > 0).toList();
    if (u9Nodes.isEmpty()) return null;
    Map<Long, Map<String, BomRawHierarchy>> frozen = new HashMap<>();
    for (var node : u9Nodes) {
      var id = node.getOriginMonthlySnapshotId();
      if (id != null) frozen.computeIfAbsent(id, key -> frozenPaths(snapshotRows.apply(key)));
    }
    // EasyData replaces the source tables and can reuse numeric IDs on unrelated rows.
    // Prefer the frozen paths; legacy nodes without detail still have to match their source.
    var legacyIds = u9Nodes.stream()
        .filter(node -> frozen.getOrDefault(node.getOriginMonthlySnapshotId(), Map.of()).isEmpty())
        .map(QuoteEffectiveBomNode::getSourceHierarchyId).filter(Objects::nonNull).distinct().toList();
    Map<Long, BomRawHierarchy> legacy = new HashMap<>();
    if (!legacyIds.isEmpty()) hierarchy.selectBatchIds(legacyIds).forEach(row -> legacy.put(row.getId(), row));
    List<BomRawHierarchy> rows = new ArrayList<>();
    for (var node : nodes) {
      var paths = frozen.getOrDefault(node.getOriginMonthlySnapshotId(), Map.of());
      var raw = "U9".equals(node.getSourceBomType()) && StringUtils.hasText(node.getSourceNodePath())
          ? (paths.isEmpty() ? legacy.get(node.getSourceHierarchyId()) : paths.get(pathKey(node.getSourceNodePath())))
          : null;
      if (raw != null && (!Objects.equals(raw.getMaterialCode(), node.getMaterialCode())
          || !Objects.equals(raw.getBuildBatchId(), node.getSourceBomBatchId())
          || !StringUtils.hasText(raw.getPath())
          || !pathKey(raw.getPath()).equals(pathKey(node.getSourceNodePath())))) raw = null;
      var occurrence = raw == null ? new BomRawHierarchy() : copy(raw);
      occurrence.setPath(node.getNodePath());
      occurrence.setLevel(node.getNodeLevel());
      occurrence.setMaterialCode(node.getMaterialCode());
      // The source hierarchy's organization is still needed for the original U9 lookup.
      if (raw == null) occurrence.setPriceOrgCode(node.getPriceOrgCode());
      rows.add(occurrence);
    }
    var bases = parentBases(rows, false);
    return bases.isEmpty() ? null : "U9_PARENT_BASE_V1:" + new TreeMap<>(bases);
  }

  private Map<String, BomRawHierarchy> frozenPaths(List<BomRawHierarchy> rows) {
    Map<String, BomRawHierarchy> result = new HashMap<>();
    for (var row : rows) {
      if (StringUtils.hasText(row.getPath())) result.put(pathKey(row.getPath()), row);
    }
    return result;
  }

  /** Freeze the verified basis alongside untouched raw usage on new monthly snapshot rows. */
  public void capture(List<BomRawHierarchy> rows) {
    var bases = parentBases(rows, true);
    for (var row : rows) {
      var base = bases.get(row.getPath());
      if (base != null) row.setFrozenU9ParentBaseQty(base);
    }
  }

  private Map<String, BigDecimal> parentBases(List<BomRawHierarchy> rows, boolean capture) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    Map<BomRawHierarchy, U9BomLineKey> lineByRow = new LinkedHashMap<>();
    Map<String, Set<String>> parents = new LinkedHashMap<>();
    for (var row : rows) {
      if (!rawU9Child(row)) continue;
      var saved = row.getFrozenU9ParentBaseQty();
      if (saved != null) {
        if (!capture && saved.signum() > 0 && saved.compareTo(BigDecimal.ONE) != 0) {
          result.put(row.getPath(), saved);
        }
        continue;
      }
      String sourceOrg = U9BomAlternativeMetadata.sourceOrganization(row);
      var key = new U9BomLineKey(sourceOrg, row.getParentCode(), row.getMaterialCode(), row.getBomPurpose(),
          row.getSortSeq(), row.getBomVersion(), row.getEffectiveFrom(), row.getEffectiveTo());
      lineByRow.put(row, key);
      parents.computeIfAbsent(sourceOrg, ignored -> new LinkedHashSet<>()).add(key.parentMaterialNo());
    }
    Map<U9BomLineKey, BomU9Source> sourceByLine = new HashMap<>();
    parents.forEach((organization, codes) -> {
      var ordered = List.copyOf(codes);
      for (int start = 0; start < ordered.size(); start += SOURCE_BATCH_SIZE) {
        var batch = ordered.subList(start, Math.min(start + SOURCE_BATCH_SIZE, ordered.size()));
        for (var source : sources.selectParentBases(organization, batch)) {
          if (source.getEffectiveFrom() == null || source.getEffectiveTo() == null
              || source.getChildSeq() == null || !StringUtils.hasText(source.getBomPurpose())
              || !StringUtils.hasText(source.getBomVersion())) continue;
          // The U9 business key is unique and survives a daily re-import. Import batch and IDs do not.
          sourceByLine.put(U9BomLineKey.from(source), source);
        }
      }
    });
    lineByRow.forEach((row, line) -> {
      var source = sourceByLine.get(line);
      if (source != null && source.getQtyPerParent() != null
          && row.getQtyPerParent().compareTo(source.getQtyPerParent()) == 0) {
        var base = source.getParentBaseQty().stripTrailingZeros();
        if (capture || base.compareTo(BigDecimal.ONE) != 0) result.put(row.getPath(), base);
      }
    });
    if (!capture && !result.isEmpty()) {
      var packagePaths = packagePaths(rows);
      result.keySet().removeIf(path -> packagePaths.stream().anyMatch(pathKey(path)::startsWith));
    }
    return result;
  }

  private Set<String> packagePaths(List<BomRawHierarchy> rows) {
    Map<String, Set<String>> codesByOrg = new LinkedHashMap<>();
    for (var row : rows) {
      if (row.getLevel() == null || row.getLevel() <= 0 || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getPriceOrgCode())) continue;
      codesByOrg.computeIfAbsent(row.getPriceOrgCode(), ignored -> new LinkedHashSet<>())
          .add(row.getMaterialCode().trim());
    }
    Map<String, Map<String, Boolean>> packageFlags = new HashMap<>();
    codesByOrg.forEach((org, codes) -> packageFlags.put(org,
        packages.batchIdentify(codes, MaterialOrganization.fromPriceOrgCode(org).getCode())));
    Set<String> paths = new LinkedHashSet<>();
    for (var row : rows) {
      if (row.getLevel() == null || row.getLevel() <= 0 || !StringUtils.hasText(row.getMaterialCode())
          || !StringUtils.hasText(row.getPath())) continue;
      var flags = packageFlags.get(row.getPriceOrgCode());
      if (flags != null && Boolean.TRUE.equals(flags.get(row.getMaterialCode().trim()))) {
        paths.add(pathKey(row.getPath()));
      }
    }
    return paths;
  }

  private static boolean rawU9Child(BomRawHierarchy row) {
    return "U9".equals(row.getSourceType()) && row.getLevel() != null && row.getLevel() > 0
        && row.getQtyPerParent() != null
        && StringUtils.hasText(row.getPath()) && StringUtils.hasText(row.getSourceImportBatchId())
        && StringUtils.hasText(row.getPriceOrgCode()) && StringUtils.hasText(row.getParentCode())
        && StringUtils.hasText(row.getMaterialCode()) && StringUtils.hasText(row.getBomPurpose())
        && StringUtils.hasText(row.getBomVersion()) && row.getSortSeq() != null
        && row.getEffectiveFrom() != null && row.getEffectiveTo() != null;
  }

  private static String pathKey(String path) {
    var key = com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.pathKey(path);
    return key + "/";
  }

  private static BomRawHierarchy copy(BomRawHierarchy row) {
    var copy = new BomRawHierarchy();
    BeanUtils.copyProperties(row, copy);
    return copy;
  }

}
