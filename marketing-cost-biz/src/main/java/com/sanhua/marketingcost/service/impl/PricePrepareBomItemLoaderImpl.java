package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.MaterialOrganization;
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
    Map<String, OrgScopedRows> scopes = new LinkedHashMap<>();
    for (BomCostingRow row : costingRows) {
      if (row == null) {
        continue;
      }
      QuoteDataOrganization organization = requiredOrganization(row);
      OrgScopedRows scope =
          scopes.computeIfAbsent(
              organization.priceOrgCode(),
              ignored ->
                  new OrgScopedRows(
                      organization,
                      new ArrayList<>(),
                      new LinkedHashSet<>(),
                      new LinkedHashSet<>(),
                      new LinkedHashSet<>()));
      scope.rows().add(row);
      String top = trimToNull(row.getTopProductCode());
      String material = trimToNull(row.getMaterialCode());
      if (top != null) {
        scope.topProductCodes().add(top);
      }
      if (top != null && material != null) {
        scope.existingKeys().add(key(top, material));
      }
      String parent = trimToNull(row.getParentCode());
      if (parent != null) {
        scope.parentCodes().add(parent);
      }
    }
    if (scopes.isEmpty()) {
      return List.of();
    }

    List<BomCostingRow> syntheticRows = new ArrayList<>();
    for (OrgScopedRows scope : scopes.values()) {
      syntheticRows.addAll(loadSyntheticPackageParentsForScope(oaNo, scope));
    }
    return syntheticRows;
  }

  private List<BomCostingRow> loadSyntheticPackageParentsForScope(String oaNo, OrgScopedRows scope) {
    if (scope.parentCodes().isEmpty() || scope.topProductCodes().isEmpty()) {
      return Collections.emptyList();
    }

    Map<String, Boolean> packageFlags =
        packageComponentIdentifyService.batchIdentify(
            scope.parentCodes(), scope.organization().materialOrganizationCode());
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
                .eq(BomRawHierarchy::getPriceOrgCode, scope.organization().priceOrgCode())
                .in(BomRawHierarchy::getTopProductCode, scope.topProductCodes())
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
    for (BomCostingRow childRow : scope.rows()) {
      if (childRow == null) {
        continue;
      }
      String top = trimToNull(childRow.getTopProductCode());
      String parent = trimToNull(childRow.getParentCode());
      if (top == null || parent == null || !packageCodes.contains(parent)) {
        continue;
      }
      String syntheticKey = key(top, parent);
      if (scope.existingKeys().contains(syntheticKey) || addedKeys.contains(syntheticKey)) {
        continue;
      }
      BomRawHierarchy raw = rawByTopAndMaterial.get(syntheticKey);
      if (raw == null) {
        continue;
      }
      BomCostingRow syntheticRow = toSyntheticCostingRow(oaNo, raw, scope.organization());
      syntheticRow.setOaFormItemId(childRow.getOaFormItemId());
      syntheticRow.setPeriodMonth(childRow.getPeriodMonth());
      syntheticRows.add(syntheticRow);
      addedKeys.add(syntheticKey);
    }
    return syntheticRows;
  }

  private QuoteDataOrganization requiredOrganization(BomCostingRow row) {
    String priceOrgCode = row == null ? null : trimToNull(row.getPriceOrgCode());
    String materialOrganizationCode =
        row == null ? null : trimToNull(row.getMaterialOrganizationCode());
    if (!StringUtils.hasText(priceOrgCode) || !StringUtils.hasText(materialOrganizationCode)) {
      throw new IllegalStateException("价格准备 BOM 行缺少上游组织");
    }
    return MaterialOrganization.normalizeQuoteDataOrganization(
        new QuoteDataOrganization(priceOrgCode, materialOrganizationCode));
  }

  private BomCostingRow toSyntheticCostingRow(
      String oaNo, BomRawHierarchy raw, QuoteDataOrganization organization) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(oaNo);
    row.setPriceOrgCode(organization.priceOrgCode());
    row.setMaterialOrganizationCode(organization.materialOrganizationCode());
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

  private record OrgScopedRows(
      QuoteDataOrganization organization,
      List<BomCostingRow> rows,
      Set<String> parentCodes,
      Set<String> topProductCodes,
      Set<String> existingKeys) {}
}
