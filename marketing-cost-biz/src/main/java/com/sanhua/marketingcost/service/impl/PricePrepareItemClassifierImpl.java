package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
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
public class PricePrepareItemClassifierImpl implements PricePrepareItemClassifier {

  public static final String ITEM_TYPE_NORMAL = "NORMAL";
  public static final String ITEM_TYPE_PACKAGE_COMPONENT = "PACKAGE_COMPONENT";
  public static final String ITEM_TYPE_MAKE_PART = "MAKE_PART";
  public static final String STATUS_READY = "READY";
  public static final String STATUS_MISSING_MASTER = "MISSING_MASTER";

  private final PackageComponentIdentifyService packageComponentIdentifyService;
  private final MaterialMasterMapper materialMasterMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final com.sanhua.marketingcost.service.costing.ProductPartCostRuleStore partCostRules;
  @org.springframework.beans.factory.annotation.Autowired
  private com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs quoteShapes;

  public PricePrepareItemClassifierImpl(
      PackageComponentIdentifyService packageComponentIdentifyService,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper) {
    this(packageComponentIdentifyService, materialMasterMapper, materialMasterRawMapper, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PricePrepareItemClassifierImpl(PackageComponentIdentifyService packageComponentIdentifyService,
      MaterialMasterMapper materialMasterMapper, MaterialMasterRawMapper materialMasterRawMapper,
      com.sanhua.marketingcost.service.costing.ProductPartCostRuleStore partCostRules) {
    this.packageComponentIdentifyService = packageComponentIdentifyService;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.partCostRules = partCostRules;
  }

  @Override
  public List<PricePreparePlanItem> classify(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> codes = collectMaterialCodes(rows);
    Map<String, Set<String>> codesByOrganization = collectMaterialCodesByOrganization(rows);
    Map<String, Boolean> packageFlags = identifyPackageComponents(codesByOrganization);
    Map<String, MaterialMaster> masters = loadMasters(codes);
    Map<String, MaterialMasterRaw> rawMasters = loadRawMasters(codesByOrganization);

    List<PricePreparePlanItem> result = new ArrayList<>(rows.size());
    var configured = new java.util.HashMap<String, List<com.sanhua.marketingcost.service.costing.ProductPartCostRule>>();
    var selectedShapes=new java.util.HashMap<String,com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs.Catalog>();
    for (BomCostingRow row : rows) {
      QuoteDataOrganization organization = requiredOrganization(row);
      var item = classifyOne(row, organization, packageFlags, masters, rawMasters);
      if (partCostRules != null) {
        String key = row.getBusinessUnitType() + "|" + row.getTopProductCode();
        var rules = configured.computeIfAbsent(key, ignored ->
            partCostRules.forProduct(row.getBusinessUnitType(), row.getTopProductCode()));
        var master = rawMasters.get(scopedKey(organization.materialOrganizationCode(), row.getMaterialCode()));
        var rule = com.sanhua.marketingcost.service.costing.ProductPartCostRule.match(rules,
            row.getMaterialCode(), master == null ? null : master.getMaterialModel());
        if (rule != null) {
          if(quoteShapes!=null) {
            String scope=row.getBusinessUnitType()+"|"+row.getOaFormItemId()+"|"+row.getPeriodMonth();
            var catalog=selectedShapes.computeIfAbsent(scope,ignored -> quoteShapes.read(row.getBusinessUnitType(),
                row.getPeriodMonth(),row.getOaFormItemId(),codes));
            var selected=catalog.choice(row.getPriceOrgCode(),row.getMaterialCode());
            if(selected!=null && selected.selectedShape()!=null)
              rule=rule.withQuotationShape(com.sanhua.marketingcost.enums.QuoteMaterialShape.valueOf(selected.selectedShape()).getLabel());
          }
          item.setSeparateRule(rule);
          item.setStatus(STATUS_READY);
          item.setItemType("制造件".equals(rule.pricingShape()) ? ITEM_TYPE_MAKE_PART : ITEM_TYPE_NORMAL);
          item.setMessage("按成品零件成本配置取基础价格");
        }
      }
      result.add(item);
    }
    return result;
  }

  private PricePreparePlanItem classifyOne(
      BomCostingRow row,
      QuoteDataOrganization organization,
      Map<String, Boolean> packageFlags,
      Map<String, MaterialMaster> masters,
      Map<String, MaterialMasterRaw> rawMasters) {
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(trimToNull(row.getTopProductCode()));
    item.setBomRowId(row.getId());
    String materialCode = trimToNull(row.getMaterialCode());
    item.setMaterialCode(materialCode);
    item.setMaterialName(resolveMaterialName(row, materialCode == null ? null : masters.get(materialCode), materialCode == null ? null : rawMasters.get(materialCode)));

    if (materialCode == null) {
      item.setMaterialCode("");
      item.setItemType(ITEM_TYPE_NORMAL);
      item.setStatus(hasText(row.getMaterialSpec()) ? STATUS_READY : STATUS_MISSING_MASTER);
      item.setMessage(hasText(row.getMaterialSpec())
          ? "BOM未填料号，按该行规格匹配价格" : "BOM未填写料号或规格，无法匹配价格");
      return item;
    }
    if (com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationBomPricing.HOUSEHOLD_ROW
        .equals(row.getSettlementRowType())) {
      item.setItemType(ITEM_TYPE_NORMAL);
      item.setStatus(STATUS_READY);
      item.setMessage("家用询价整体计价");
      return item;
    }
    if (Boolean.TRUE.equals(packageFlags.get(
        scopedKey(organization.materialOrganizationCode(), materialCode)))) {
      item.setItemType(ITEM_TYPE_PACKAGE_COMPONENT);
      item.setStatus(STATUS_READY);
      item.setMessage("包装组件待准备，后续按顶级成品+包装父料号展开子件");
      return item;
    }

    MaterialMaster master = masters.get(materialCode);
    MaterialMasterRaw raw = rawMasters.get(scopedKey(
        organization.materialOrganizationCode(), materialCode));
    if (master == null && raw == null && !hasText(row.getMaterialName()) && !hasText(row.getShapeAttr())) {
      item.setItemType(ITEM_TYPE_NORMAL);
      item.setStatus(STATUS_MISSING_MASTER);
      item.setMessage("缺料品主档，无法判断料号类型");
      return item;
    }

    String shapeAttr = firstText(
        row.getShapeAttr(),
        master == null ? null : master.getShapeAttr(),
        raw == null ? null : raw.getShapeAttr());
    if (isManufacturedText(shapeAttr)) {
      item.setItemType(ITEM_TYPE_MAKE_PART);
      item.setStatus(STATUS_READY);
      item.setMessage("自制件待准备");
      return item;
    }

    item.setItemType(ITEM_TYPE_NORMAL);
    item.setStatus(STATUS_READY);
    item.setMessage("普通料号待准备");
    return item;
  }

  private Set<String> collectMaterialCodes(List<BomCostingRow> rows) {
    Set<String> codes = new LinkedHashSet<>();
    for (BomCostingRow row : rows) {
      if (row == null) {
        continue;
      }
      String code = trimToNull(row.getMaterialCode());
      if (code != null) {
        codes.add(code);
      }
    }
    return codes;
  }

  private Map<String, Set<String>> collectMaterialCodesByOrganization(List<BomCostingRow> rows) {
    Map<String, Set<String>> result = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (row == null) {
        continue;
      }
      String code = trimToNull(row.getMaterialCode());
      if (code == null) {
        continue;
      }
      QuoteDataOrganization organization = requiredOrganization(row);
      result
          .computeIfAbsent(organization.materialOrganizationCode(), ignored -> new LinkedHashSet<>())
          .add(code);
    }
    return result;
  }

  private Map<String, MaterialMaster> loadMasters(Set<String> codes) {
    if (codes.isEmpty()) {
      return Collections.emptyMap();
    }
    List<MaterialMaster> rows =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class).in(MaterialMaster::getMaterialCode, codes));
    Map<String, MaterialMaster> result = new LinkedHashMap<>();
    for (MaterialMaster row : rows) {
      String code = row == null ? null : trimToNull(row.getMaterialCode());
      if (code != null) {
        result.putIfAbsent(code, row);
      }
    }
    return result;
  }

  private Map<String, MaterialMasterRaw> loadRawMasters(
      Map<String, Set<String>> codesByOrganization) {
    if (codesByOrganization.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, MaterialMasterRaw> result = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      List<MaterialMasterRaw> rows = selectRawRows(entry.getValue(), entry.getKey());
      if (rows == null) {
        continue;
      }
      for (MaterialMasterRaw row : rows) {
        String code = row == null ? null : trimToNull(row.getMaterialCode());
        if (code != null) {
          result.putIfAbsent(scopedKey(entry.getKey(), code), row);
        }
      }
    }
    return result;
  }

  private List<MaterialMasterRaw> selectRawRows(Set<String> codes, String organizationCode) {
    String organization = requiredMaterialOrganizationCode(organizationCode);
    return materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null, organization);
  }

  private Map<String, Boolean> identifyPackageComponents(
      Map<String, Set<String>> codesByOrganization) {
    if (codesByOrganization.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      String organization = requiredMaterialOrganizationCode(entry.getKey());
      Map<String, Boolean> flags =
          packageComponentIdentifyService.batchIdentify(entry.getValue(), organization);
      if (flags == null) {
        continue;
      }
      for (Map.Entry<String, Boolean> flag : flags.entrySet()) {
        String code = trimToNull(flag.getKey());
        if (code != null) {
          result.put(scopedKey(organization, code), flag.getValue());
        }
      }
    }
    return result;
  }

  private QuoteDataOrganization requiredOrganization(BomCostingRow row) {
    String priceOrgCode = row == null ? null : trimToNull(row.getPriceOrgCode());
    String materialOrganizationCode =
        row == null ? null : trimToNull(row.getMaterialOrganizationCode());
    if (priceOrgCode == null || materialOrganizationCode == null) {
      throw new IllegalStateException("价格准备 BOM 行缺少上游组织");
    }
    return MaterialOrganization.normalizeQuoteDataOrganization(
        new QuoteDataOrganization(priceOrgCode, materialOrganizationCode));
  }

  private String requiredMaterialOrganizationCode(String organizationCode) {
    String normalized = trimToNull(organizationCode);
    if (normalized == null) {
      throw new IllegalStateException("价格准备 BOM 行缺少上游 materialOrganizationCode");
    }
    return MaterialOrganization.fromCode(normalized).getCode();
  }

  private String scopedKey(String organizationCode, String materialCode) {
    return requiredMaterialOrganizationCode(organizationCode) + "|" + materialCode;
  }

  private String resolveMaterialName(BomCostingRow row, MaterialMaster master, MaterialMasterRaw raw) {
    return firstText(
        row.getMaterialName(),
        master == null ? null : master.getMaterialName(),
        raw == null ? null : raw.getMaterialName());
  }

  private boolean isManufacturedText(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    String normalized = value.trim();
    return MaterialFormAttrEnum.fromDbText(normalized)
        .map(MaterialFormAttrEnum.MANUFACTURED::equals)
        .orElse("自制".equals(normalized) || "原材料联动".equals(normalized));
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        return trimmed;
      }
    }
    return null;
  }

  private boolean hasText(String value) {
    return StringUtils.hasText(value);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
