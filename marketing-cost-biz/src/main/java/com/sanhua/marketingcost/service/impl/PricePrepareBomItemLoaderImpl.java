package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PricePrepareBomItemLoaderImpl implements PricePrepareBomItemLoader {

  private final BomCostingRowMapper bomCostingRowMapper;
  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final PackageComponentIdentifyService packageComponentIdentifyService;

  public PricePrepareBomItemLoaderImpl(
      BomCostingRowMapper bomCostingRowMapper,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      PackageComponentIdentifyService packageComponentIdentifyService) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.packageComponentIdentifyService = packageComponentIdentifyService;
  }

  @Override
  public List<BomCostingRow> loadByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<BomCostingRow> costingRows = bomCostingRowMapper.selectList(
        Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getOaNo, oaNoValue)
            .orderByAsc(BomCostingRow::getTopProductCode)
            .orderByAsc(BomCostingRow::getId));
    if (costingRows == null || costingRows.isEmpty()) {
      return Collections.emptyList();
    }
    List<BomCostingRow> rows = new ArrayList<>(costingRows);
    rows.addAll(loadSyntheticPackageParents(oaNoValue, costingRows));
    return rows;
  }

  @Override
  public List<BomCostingRow> loadByOaNoAndTopProducts(String oaNo, List<String> topProductCodes) {
    Set<String> tops = normalizeTopProductCodes(topProductCodes);
    if (tops.isEmpty()) {
      return loadByOaNo(oaNo);
    }
    List<BomCostingRow> rows = loadByOaNo(oaNo);
    if (rows.isEmpty()) {
      return rows;
    }
    return rows.stream()
        .filter(row -> row != null && tops.contains(trimToNull(row.getTopProductCode())))
        .toList();
  }

  private List<BomCostingRow> loadSyntheticPackageParents(String oaNo, List<BomCostingRow> costingRows) {
    Set<String> parentCodes = new LinkedHashSet<>();
    Set<String> topProductCodes = new LinkedHashSet<>();
    Set<String> existingKeys = new LinkedHashSet<>();
    for (BomCostingRow row : costingRows) {
      if (row == null) {
        continue;
      }
      String top = trimToNull(row.getTopProductCode());
      String material = trimToNull(row.getMaterialCode());
      if (top != null) {
        topProductCodes.add(top);
      }
      if (top != null && material != null) {
        existingKeys.add(key(top, material));
      }
      String parent = trimToNull(row.getParentCode());
      if (parent != null) {
        parentCodes.add(parent);
      }
    }
    if (parentCodes.isEmpty() || topProductCodes.isEmpty()) {
      return Collections.emptyList();
    }

    Map<String, Boolean> packageFlags = packageComponentIdentifyService.batchIdentify(parentCodes);
    Set<String> packageCodes = new LinkedHashSet<>();
    for (Map.Entry<String, Boolean> entry : packageFlags.entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue())) {
        packageCodes.add(entry.getKey());
      }
    }
    if (packageCodes.isEmpty()) {
      return Collections.emptyList();
    }

    List<BomRawHierarchy> rawParents =
        bomRawHierarchyMapper.selectList(
            Wrappers.lambdaQuery(BomRawHierarchy.class)
                .in(BomRawHierarchy::getTopProductCode, topProductCodes)
                .in(BomRawHierarchy::getMaterialCode, packageCodes)
                .orderByAsc(BomRawHierarchy::getTopProductCode)
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getId));
    if (rawParents == null || rawParents.isEmpty()) {
      return Collections.emptyList();
    }
    Map<String, BomRawHierarchy> rawByTopAndMaterial = new LinkedHashMap<>();
    for (BomRawHierarchy raw : rawParents) {
      if (raw == null) {
        continue;
      }
      String top = trimToNull(raw.getTopProductCode());
      String material = trimToNull(raw.getMaterialCode());
      if (top != null && material != null) {
        rawByTopAndMaterial.putIfAbsent(key(top, material), raw);
      }
    }

    List<BomCostingRow> syntheticRows = new ArrayList<>();
    Set<String> addedKeys = new LinkedHashSet<>();
    for (BomCostingRow childRow : costingRows) {
      if (childRow == null) {
        continue;
      }
      String top = trimToNull(childRow.getTopProductCode());
      String parent = trimToNull(childRow.getParentCode());
      if (top == null || parent == null || !packageCodes.contains(parent)) {
        continue;
      }
      String syntheticKey = key(top, parent);
      if (existingKeys.contains(syntheticKey) || addedKeys.contains(syntheticKey)) {
        continue;
      }
      BomRawHierarchy raw = rawByTopAndMaterial.get(syntheticKey);
      if (raw == null) {
        continue;
      }
      syntheticRows.add(toSyntheticCostingRow(oaNo, raw));
      addedKeys.add(syntheticKey);
    }
    return syntheticRows;
  }

  private BomCostingRow toSyntheticCostingRow(String oaNo, BomRawHierarchy raw) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(oaNo);
    row.setTopProductCode(raw.getTopProductCode());
    row.setParentCode(raw.getParentCode());
    row.setMaterialCode(raw.getMaterialCode());
    row.setLevel(raw.getLevel());
    row.setPath(raw.getPath());
    row.setQtyPerParent(raw.getQtyPerParent());
    row.setQtyPerTop(raw.getQtyPerTop());
    row.setIsCostingRow(1);
    row.setSubtreeCostRequired(1);
    row.setRawHierarchyNodeId(raw.getId());
    row.setMaterialName(raw.getMaterialName());
    row.setMaterialSpec(raw.getMaterialSpec());
    row.setShapeAttr(raw.getShapeAttr());
    row.setSourceCategory(raw.getSourceCategory());
    row.setCostElementCode(raw.getCostElementCode());
    row.setBomPurpose(raw.getBomPurpose());
    row.setBomVersion(raw.getBomVersion());
    row.setU9IsCostFlag(raw.getU9IsCostFlag());
    row.setEffectiveFrom(raw.getEffectiveFrom());
    row.setEffectiveTo(raw.getEffectiveTo());
    row.setBuildBatchId(raw.getBuildBatchId());
    row.setBuiltAt(raw.getBuiltAt());
    row.setBusinessUnitType(raw.getBusinessUnitType());
    return row;
  }

  private String key(String topProductCode, String materialCode) {
    return topProductCode + "\u0000" + materialCode;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private Set<String> normalizeTopProductCodes(List<String> topProductCodes) {
    if (topProductCodes == null || topProductCodes.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> codes = new LinkedHashSet<>();
    for (String code : topProductCodes) {
      String value = trimToNull(code);
      if (value != null) {
        codes.add(value);
      }
    }
    return codes;
  }
}
