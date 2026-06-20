package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
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
  private final OaFormItemMapper oaFormItemMapper;
  private final PackageComponentIdentifyService packageComponentIdentifyService;

  public PricePrepareBomItemLoaderImpl(
      BomCostingRowMapper bomCostingRowMapper,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      OaFormItemMapper oaFormItemMapper,
      PackageComponentIdentifyService packageComponentIdentifyService) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.oaFormItemMapper = oaFormItemMapper;
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

  @Override
  public List<BomCostingRow> loadByQuoteItem(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    if (!StringUtils.hasText(oaNo)
        || oaFormItemId == null
        || !StringUtils.hasText(topProductCode)
        || !StringUtils.hasText(periodMonth)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    String topProductCodeValue = topProductCode.trim();
    String periodMonthValue = periodMonth.trim();
    List<BomCostingRow> costingRows =
        bomCostingRowMapper.selectQuoteCostingSnapshot(
            oaNoValue, oaFormItemId, topProductCodeValue, periodMonthValue);
    if (costingRows == null || costingRows.isEmpty()) {
      return Collections.emptyList();
    }
    List<BomCostingRow> rows = new ArrayList<>(costingRows);
    rows.addAll(loadSyntheticPackageParents(oaNoValue, costingRows));
    return rows;
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

    Map<String, Boolean> packageFlags =
        packageComponentIdentifyService.batchIdentify(
            parentCodes, resolveMaterialOrganization(oaNo, costingRows));
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
      BomCostingRow syntheticRow = toSyntheticCostingRow(oaNo, raw);
      syntheticRow.setOaFormItemId(childRow.getOaFormItemId());
      syntheticRow.setPeriodMonth(childRow.getPeriodMonth());
      syntheticRows.add(syntheticRow);
      addedKeys.add(syntheticKey);
    }
    return syntheticRows;
  }

  private String resolveMaterialOrganization(String oaNo, List<BomCostingRow> costingRows) {
    if (costingRows == null || costingRows.isEmpty()) {
      return MaterialOrganization.forQuoteProcess(null, oaNo);
    }
    Map<Long, String> productNames = new LinkedHashMap<>();
    for (BomCostingRow row : costingRows) {
      if (row == null) {
        continue;
      }
      String organization =
          MaterialOrganization.forQuoteProcess(
              null, row.getOaNo(), resolveProductName(row.getOaFormItemId(), productNames));
      if (MaterialOrganization.PLATE.getCode().equals(organization)) {
        return organization;
      }
    }
    return MaterialOrganization.forQuoteProcess(null, oaNo);
  }

  private String resolveProductName(Long oaFormItemId, Map<Long, String> productNames) {
    if (oaFormItemId == null) {
      return null;
    }
    if (productNames.containsKey(oaFormItemId)) {
      return productNames.get(oaFormItemId);
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    String productName = item == null ? null : item.getProductName();
    productNames.put(oaFormItemId, productName);
    return productName;
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
