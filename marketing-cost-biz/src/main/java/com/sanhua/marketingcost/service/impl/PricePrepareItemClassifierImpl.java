package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
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

  public PricePrepareItemClassifierImpl(
      PackageComponentIdentifyService packageComponentIdentifyService,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper) {
    this.packageComponentIdentifyService = packageComponentIdentifyService;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public List<PricePreparePlanItem> classify(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyList();
    }
    Set<String> codes = collectMaterialCodes(rows);
    Map<String, Boolean> packageFlags = packageComponentIdentifyService.batchIdentify(codes);
    Map<String, MaterialMaster> masters = loadMasters(codes);
    Map<String, MaterialMasterRaw> rawMasters = loadRawMasters(codes);

    List<PricePreparePlanItem> result = new ArrayList<>(rows.size());
    for (BomCostingRow row : rows) {
      result.add(classifyOne(row, packageFlags, masters, rawMasters));
    }
    return result;
  }

  private PricePreparePlanItem classifyOne(
      BomCostingRow row,
      Map<String, Boolean> packageFlags,
      Map<String, MaterialMaster> masters,
      Map<String, MaterialMasterRaw> rawMasters) {
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(trimToNull(row.getTopProductCode()));
    item.setBomRowId(row.getId());
    String materialCode = trimToNull(row.getMaterialCode());
    item.setMaterialCode(materialCode);
    item.setMaterialName(resolveMaterialName(row, masters.get(materialCode), rawMasters.get(materialCode)));

    if (materialCode == null) {
      item.setItemType(ITEM_TYPE_NORMAL);
      item.setStatus(STATUS_MISSING_MASTER);
      item.setMessage("BOM结算行缺料号，无法进入价格准备");
      return item;
    }
    if (Boolean.TRUE.equals(packageFlags.get(materialCode))) {
      item.setItemType(ITEM_TYPE_PACKAGE_COMPONENT);
      item.setStatus(STATUS_READY);
      item.setMessage("包装组件待准备，后续按顶级成品+包装父料号展开子件");
      return item;
    }

    MaterialMaster master = masters.get(materialCode);
    MaterialMasterRaw raw = rawMasters.get(materialCode);
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

  private Map<String, MaterialMasterRaw> loadRawMasters(Set<String> codes) {
    if (codes.isEmpty()) {
      return Collections.emptyMap();
    }
    List<MaterialMasterRaw> rows = materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null);
    Map<String, MaterialMasterRaw> result = new LinkedHashMap<>();
    if (rows == null) {
      return result;
    }
    for (MaterialMasterRaw row : rows) {
      String code = row == null ? null : trimToNull(row.getMaterialCode());
      if (code != null) {
        result.putIfAbsent(code, row);
      }
    }
    return result;
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
