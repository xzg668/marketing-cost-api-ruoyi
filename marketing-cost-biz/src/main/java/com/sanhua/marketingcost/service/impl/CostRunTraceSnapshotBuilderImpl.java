package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunTraceSnapshotBuilderImpl implements CostRunTraceSnapshotBuilder {

  private static final String TRACE_TYPE_PART_PRICE = "PART_PRICE";
  private static final String TRACE_TYPE_COST_ITEM = "COST_ITEM";
  private static final String TRACE_TYPE_TOTAL = "TOTAL";
  private static final String SOURCE_TYPE_LINKED_PRICE = "LINKED_PRICE";
  private static final String SOURCE_TYPE_MAKE_PART = "MAKE_PART";
  private static final String SOURCE_TYPE_PACKAGE_COMPONENT = "PACKAGE_COMPONENT";
  private static final String SOURCE_TYPE_FIXED_PRICE = "FIXED_PRICE";
  private static final String SOURCE_TYPE_SETTLE_FIXED_PRICE = "SETTLE_FIXED_PRICE";
  private static final String SOURCE_TYPE_RANGE_PRICE = "RANGE_PRICE";
  private static final String COST_TOTAL = "TOTAL";
  private static final String COST_MATERIAL = "MATERIAL";
  private static final String COST_DIRECT_LABOR = "DIRECT_LABOR";
  private static final String COST_INDIRECT_LABOR = "INDIRECT_LABOR";
  private static final String COST_LOSS = "LOSS";
  private static final String COST_MANUFACTURE = "MANUFACTURE";
  private static final String COST_MANUFACTURE_COST = "MANUFACTURE_COST";
  private static final String COST_ADJUSTED_MANUFACTURE_COST = "ADJUSTED_MANUFACTURE_COST";
  private static final String COST_MGMT_EXP = "MGMT_EXP";
  private static final String COST_SALES_EXP = "SALES_EXP";
  private static final String COST_FIN_EXP = "FIN_EXP";
  private static final String COST_OVERHAUL = "OVERHAUL";
  private static final String COST_TOOLING_REPAIR = "TOOLING_REPAIR";
  private static final String COST_WATER_POWER = "WATER_POWER";
  private static final String COST_DEPT_OTHER = "DEPT_OTHER";
  private static final String COST_OTHER_EXP_PACKAGE = "OTHER_EXP_PACKAGE";
  private static final String PROCESS_TYPE_BLANK = "毛坯加工";
  private static final String STATUS_PENDING_DETAIL = "PENDING_DETAIL";
  private static final BigDecimal G_TO_KG = new BigDecimal("1000");
  private static final int AMOUNT_SCALE = 8;
  private static final int JSON_MAX_LENGTH = 65535;
  private static final Pattern VARIABLE_TOKEN = Pattern.compile("\\[([^\\]]+)]");

  private final CostRunPartItemMapper partItemMapper;
  private final CostRunCostItemMapper costItemMapper;
  private final PricePrepareItemMapper pricePrepareItemMapper;
  private final MakePartPriceCalcRowMapper makePartPriceCalcRowMapper;
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final PriceFixedItemMapper priceFixedItemMapper;
  private final PriceRangeItemMapper priceRangeItemMapper;
  private final ObjectMapper objectMapper;

  public CostRunTraceSnapshotBuilderImpl(
      CostRunPartItemMapper partItemMapper,
      CostRunCostItemMapper costItemMapper,
      PricePrepareItemMapper pricePrepareItemMapper,
      MakePartPriceCalcRowMapper makePartPriceCalcRowMapper,
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      PriceFixedItemMapper priceFixedItemMapper,
      PriceRangeItemMapper priceRangeItemMapper,
      ObjectMapper objectMapper) {
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
    this.pricePrepareItemMapper = pricePrepareItemMapper;
    this.makePartPriceCalcRowMapper = makePartPriceCalcRowMapper;
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.priceFixedItemMapper = priceFixedItemMapper;
    this.priceRangeItemMapper = priceRangeItemMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<CostRunTraceSnapshot> build(QuoteCostRunVersion version) {
    if (version == null || !StringUtils.hasText(version.getCostRunNo())) {
      return List.of();
    }
    String costRunNo = version.getCostRunNo().trim();
    List<CostRunPartItem> parts =
        partItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getCostRunNo, costRunNo)
                .orderByAsc(CostRunPartItem::getId));
    List<CostRunCostItem> costs =
        costItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getCostRunNo, costRunNo)
                .orderByAsc(CostRunCostItem::getLineNo, CostRunCostItem::getId));
    Map<Long, PricePrepareItem> prepareItems = loadPrepareItems(parts);
    List<CostRunTraceSnapshot> snapshots = new ArrayList<>();
    for (CostRunPartItem part : parts) {
      if (part == null) {
        continue;
      }
      snapshots.add(buildPartSnapshot(version, part, prepareItems.get(part.getPricePrepareItemId())));
    }
    for (CostRunCostItem cost : costs) {
      if (cost == null) {
        continue;
      }
      snapshots.add(buildCostSnapshot(version, cost, costs));
    }
    return snapshots;
  }

  private Map<Long, PricePrepareItem> loadPrepareItems(List<CostRunPartItem> parts) {
    List<Long> ids =
        parts.stream()
            .filter(Objects::nonNull)
            .map(CostRunPartItem::getPricePrepareItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return pricePrepareItemMapper
        .selectList(Wrappers.lambdaQuery(PricePrepareItem.class).in(PricePrepareItem::getId, ids))
        .stream()
        .filter(Objects::nonNull)
        .filter(item -> item.getId() != null)
        .collect(Collectors.toMap(PricePrepareItem::getId, Function.identity(), (left, right) -> left));
  }

  private CostRunTraceSnapshot buildPartSnapshot(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    CostRunTraceSnapshot snapshot = baseSnapshot(version);
    snapshot.setTraceType(TRACE_TYPE_PART_PRICE);
    snapshot.setTraceKey(partTraceKey(part));
    snapshot.setPartItemId(part.getId());
    snapshot.setBomRowId(part.getBomRowId());
    snapshot.setPricePrepareItemId(part.getPricePrepareItemId());
    snapshot.setMaterialCode(trimToNull(part.getPartCode()));
    snapshot.setMaterialName(trimToNull(part.getPartName()));
    String sourceType = partSourceType(part, prepareItem);
    snapshot.setSourceType(sourceType);
    snapshot.setSourceBatchNo(prepareItem == null ? null : trimToNull(prepareItem.getPrepareNo()));
    snapshot.setSourceRefId(prepareItem == null ? null : prepareItem.getResultRefId());
    snapshot.setUnitPrice(part.getUnitPrice());
    snapshot.setQuantity(part.getQty());
    snapshot.setAmount(part.getAmount());
    snapshot.setSummary(partSummary(part, prepareItem));
    if (SOURCE_TYPE_LINKED_PRICE.equals(sourceType)) {
      PriceLinkedCalcItem linkedCalc = loadLinkedCalc(version, part, prepareItem);
      PriceLinkedItem linkedItem = loadLinkedItem(version, part, prepareItem, linkedCalc);
      Map<String, Object> trace = linkedTraceMap(linkedCalc);
      if (linkedCalc != null && linkedCalc.getId() != null) {
        snapshot.setSourceRefId(linkedCalc.getId());
      }
      snapshot.setSourceSnapshotJson(json(linkedSourceSnapshot(part, prepareItem, linkedCalc, linkedItem, trace)));
      snapshot.setFormulaSnapshotJson(json(linkedFormula(part, linkedCalc, linkedItem, trace)));
      snapshot.setVariablesJson(json(linkedVariables(part, linkedCalc, linkedItem, trace)));
      snapshot.setStepsJson(json(linkedSteps(part, linkedCalc, linkedItem, trace)));
    } else if (SOURCE_TYPE_FIXED_PRICE.equals(sourceType)
        || SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType)) {
      PriceFixedItem fixedItem = loadFixedPriceItem(version, part, prepareItem, sourceType);
      if (fixedItem != null && fixedItem.getId() != null) {
        snapshot.setSourceRefId(fixedItem.getId());
      }
      snapshot.setSourceSnapshotJson(json(fixedSourceSnapshot(part, prepareItem, fixedItem, sourceType)));
      snapshot.setFormulaSnapshotJson(json(fixedFormula(part, fixedItem, sourceType)));
      snapshot.setVariablesJson(json(fixedVariables(part, fixedItem, sourceType)));
      snapshot.setStepsJson(json(fixedSteps(part, fixedItem, sourceType)));
    } else if (SOURCE_TYPE_RANGE_PRICE.equals(sourceType)) {
      PriceRangeItem rangeItem = loadRangePriceItem(version, part, prepareItem);
      if (rangeItem != null && rangeItem.getId() != null) {
        snapshot.setSourceRefId(rangeItem.getId());
      }
      snapshot.setSourceSnapshotJson(json(rangeSourceSnapshot(part, prepareItem, rangeItem)));
      snapshot.setFormulaSnapshotJson(json(rangeFormula(part, rangeItem)));
      snapshot.setVariablesJson(json(rangeVariables(part, rangeItem)));
      snapshot.setStepsJson(json(rangeSteps(part, rangeItem)));
    } else if (SOURCE_TYPE_MAKE_PART.equals(sourceType)) {
      List<MakePartPriceCalcRow> rows = loadMakePartCalcRows(version, part, prepareItem);
      snapshot.setSourceSnapshotJson(json(makePartSourceSnapshot(part, prepareItem, rows)));
      snapshot.setFormulaSnapshotJson(json(makePartAggregateFormula(part)));
      snapshot.setVariablesJson(json(makePartAggregateVariables(part, prepareItem, rows)));
      snapshot.setStepsJson(json(makePartAggregateSteps(part, rows)));
      snapshot.setChildrenJson(json(makePartChildren(rows, part.getPricePrepareItemId())));
    } else {
      snapshot.setSourceSnapshotJson(json(partSourceSnapshot(part, prepareItem)));
      snapshot.setFormulaSnapshotJson(json(partFormula(part)));
      snapshot.setVariablesJson(json(partVariables(part, prepareItem)));
      snapshot.setStepsJson(json(partSteps(part)));
    }
    if (SOURCE_TYPE_PACKAGE_COMPONENT.equals(sourceType)) {
      snapshot.setChildrenJson(json(List.of(pendingDetail(
          "T5 后续按包装组件来源补充子件或公式明细",
          part.getPricePrepareItemId()))));
    }
    return snapshot;
  }

  private CostRunTraceSnapshot buildCostSnapshot(
      QuoteCostRunVersion version, CostRunCostItem cost, List<CostRunCostItem> allCosts) {
    CostRunTraceSnapshot snapshot = baseSnapshot(version);
    String costCode = trimToNull(cost.getCostCode());
    boolean total = COST_TOTAL.equals(costCode);
    snapshot.setTraceType(total ? TRACE_TYPE_TOTAL : TRACE_TYPE_COST_ITEM);
    snapshot.setTraceKey(total ? COST_TOTAL : costTraceKey(cost));
    snapshot.setCostItemId(cost.getId());
    snapshot.setCostCode(costCode);
    snapshot.setCostName(trimToNull(cost.getCostName()));
    snapshot.setSourceType(costSourceType(costCode));
    snapshot.setSourceBatchNo(trimToNull(cost.getSourceTable()));
    snapshot.setSourceRefId(cost.getSourceId());
    snapshot.setBaseAmount(cost.getBaseAmount());
    snapshot.setRate(cost.getRate());
    snapshot.setAmount(cost.getAmount());
    snapshot.setSummary(costSummary(cost));
    snapshot.setSourceSnapshotJson(json(costSourceSnapshot(cost)));
    snapshot.setFormulaSnapshotJson(json(costFormula(costCode)));
    snapshot.setVariablesJson(json(costVariables(cost)));
    snapshot.setStepsJson(json(costSteps(cost, costCode)));
    if (costHasChildren(costCode)) {
      snapshot.setChildrenJson(json(costChildren(costCode, allCosts)));
    }
    return snapshot;
  }

  private CostRunTraceSnapshot baseSnapshot(QuoteCostRunVersion version) {
    CostRunTraceSnapshot snapshot = new CostRunTraceSnapshot();
    snapshot.setCostRunVersionId(version.getId());
    snapshot.setCostRunNo(trimToNull(version.getCostRunNo()));
    snapshot.setVersionNo(trimToNull(version.getVersionNo()));
    snapshot.setOaNo(trimToNull(version.getOaNo()));
    snapshot.setOaFormItemId(version.getOaFormItemId());
    snapshot.setProductCode(trimToNull(version.getProductCode()));
    snapshot.setPricingMonth(trimToNull(version.getPricingMonth()));
    snapshot.setBusinessUnitType(trimToNull(version.getBusinessUnitType()));
    return snapshot;
  }

  private String partTraceKey(CostRunPartItem part) {
    if (part.getId() != null) {
      return "PART:" + part.getId();
    }
    if (part.getBomRowId() != null) {
      return "PART_BOM:" + part.getBomRowId();
    }
    return "PART:" + nullSafe(part.getPartCode());
  }

  private String costTraceKey(CostRunCostItem cost) {
    if (StringUtils.hasText(cost.getCostCode())) {
      return "COST:" + cost.getCostCode().trim();
    }
    return "COST:" + cost.getId();
  }

  private String partSourceType(CostRunPartItem part, PricePrepareItem prepareItem) {
    String raw =
        firstText(
            prepareItem == null ? null : prepareItem.getPriceSource(),
            part.getPriceSource(),
            prepareItem == null ? null : prepareItem.getResultRefType());
    String upper = raw == null ? "" : raw.trim().toUpperCase();
    String text = raw == null ? "" : raw.trim();
    if (upper.contains("LINK") || upper.contains("联动")) {
      return SOURCE_TYPE_LINKED_PRICE;
    }
    if (upper.contains("MAKE") || upper.contains("自制") || upper.contains("制造")) {
      return SOURCE_TYPE_MAKE_PART;
    }
    if (upper.contains("PACK") || upper.contains("包装")) {
      return SOURCE_TYPE_PACKAGE_COMPONENT;
    }
    if (upper.contains("RANGE") || text.contains("区间")) {
      return SOURCE_TYPE_RANGE_PRICE;
    }
    if (upper.contains("SETTLE") || text.contains("结算")) {
      return SOURCE_TYPE_SETTLE_FIXED_PRICE;
    }
    if (upper.contains("FIX") || upper.contains("固定")) {
      return SOURCE_TYPE_FIXED_PRICE;
    }
    if (StringUtils.hasText(raw)) {
      return raw.trim();
    }
    return "UNKNOWN_PRICE";
  }

  private String costSourceType(String costCode) {
    if (COST_MATERIAL.equals(costCode)
        || COST_MANUFACTURE_COST.equals(costCode)
        || COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)
        || COST_TOTAL.equals(costCode)) {
      return "ROLLUP";
    }
    if (COST_DIRECT_LABOR.equals(costCode) || COST_INDIRECT_LABOR.equals(costCode)) {
      return "CMS";
    }
    if (COST_LOSS.equals(costCode)
        || COST_MANUFACTURE.equals(costCode)
        || COST_MGMT_EXP.equals(costCode)
        || COST_SALES_EXP.equals(costCode)
        || COST_FIN_EXP.equals(costCode)
        || startsWith(costCode, "OTHER_EXP_")) {
      return "RATE_CONFIG";
    }
    if (startsWith(costCode, "AUX_")
        || "OVERHAUL".equals(costCode)
        || "TOOLING_REPAIR".equals(costCode)
        || "WATER_POWER".equals(costCode)
        || "DEPT_OTHER".equals(costCode)) {
      return "CMS";
    }
    return "ROLLUP";
  }

  private Map<String, Object> partSourceSnapshot(CostRunPartItem part, PricePrepareItem prepareItem) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("partItem", mapOf(
        "id", part.getId(),
        "bomRowId", part.getBomRowId(),
        "pricePrepareItemId", part.getPricePrepareItemId(),
        "productCode", part.getProductCode(),
        "partCode", part.getPartCode(),
        "partName", part.getPartName(),
        "priceSource", part.getPriceSource(),
        "remark", part.getRemark()));
    if (prepareItem != null) {
      payload.put("pricePrepareItem", mapOf(
          "id", prepareItem.getId(),
          "prepareNo", prepareItem.getPrepareNo(),
          "periodMonth", prepareItem.getPeriodMonth(),
          "priceTypeConfirmNo", prepareItem.getPriceTypeConfirmNo(),
          "priceTypeConfirmItemId", prepareItem.getPriceTypeConfirmItemId(),
          "bomRowId", prepareItem.getBomRowId(),
          "materialCode", prepareItem.getMaterialCode(),
          "itemType", prepareItem.getItemType(),
          "unitPrice", prepareItem.getUnitPrice(),
          "amount", prepareItem.getAmount(),
          "priceSource", prepareItem.getPriceSource(),
          "status", prepareItem.getStatus(),
          "resultRefType", prepareItem.getResultRefType(),
          "resultRefId", prepareItem.getResultRefId(),
          "message", prepareItem.getMessage()));
    }
    return payload;
  }

  private Map<String, Object> partFormula(CostRunPartItem part) {
    return mapOf(
        "formula", "amount = unitPrice * quantity",
        "displayFormula", "金额 = 单价 × 数量",
        "priceSource", part.getPriceSource());
  }

  private Map<String, Object> partVariables(CostRunPartItem part, PricePrepareItem prepareItem) {
    return mapOf(
        "unitPrice", part.getUnitPrice(),
        "quantity", part.getQty(),
        "amount", part.getAmount(),
        "pricePrepareUnitPrice", prepareItem == null ? null : prepareItem.getUnitPrice(),
        "pricePrepareAmount", prepareItem == null ? null : prepareItem.getAmount(),
        "remark", firstText(part.getRemark(), prepareItem == null ? null : prepareItem.getMessage()));
  }

  private List<Map<String, Object>> partSteps(CostRunPartItem part) {
    return List.of(mapOf(
        "step", "PART_AMOUNT",
        "formula", "unitPrice * quantity",
        "unitPrice", part.getUnitPrice(),
        "quantity", part.getQty(),
        "amount", part.getAmount()));
  }

  private PriceFixedItem loadFixedPriceItem(
      QuoteCostRunVersion version,
      CostRunPartItem part,
      PricePrepareItem prepareItem,
      String sourceType) {
    if (prepareItem != null && prepareItem.getResultRefId() != null) {
      PriceFixedItem row = priceFixedItemMapper.selectById(prepareItem.getResultRefId());
      if (row != null) {
        return row;
      }
    }
    String materialCode =
        firstText(part.getPartCode(), prepareItem == null ? null : prepareItem.getMaterialCode());
    if (materialCode == null) {
      return null;
    }
    boolean settle = SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType);
    LambdaQueryWrapper<PriceFixedItem> query =
        Wrappers.lambdaQuery(PriceFixedItem.class)
            .eq(PriceFixedItem::getMaterialCode, materialCode)
            .in(
                PriceFixedItem::getSourceType,
                settle ? List.of("SETTLE_FIXED", "SETTLE") : List.of("PURCHASE_FIXED", "PURCHASE"))
            .isNotNull(PriceFixedItem::getFixedPrice);
    LocalDate priceDate = priceDate(version, prepareItem);
    if (priceDate != null) {
      query.and(q -> q.le(PriceFixedItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceFixedItem::getEffectiveFrom));
      query.and(q -> q.ge(PriceFixedItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceFixedItem::getEffectiveTo));
    }
    eqIfText(query, PriceFixedItem::getBusinessUnitType,
        firstText(part.getBusinessUnitType(), version.getBusinessUnitType()));
    List<PriceFixedItem> rows =
        priceFixedItemMapper.selectList(
            query.orderByDesc(PriceFixedItem::getEffectiveFrom)
                .orderByDesc(PriceFixedItem::getPricingMonth)
                .orderByDesc(PriceFixedItem::getId));
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    return rows.stream()
        .filter(row -> sameAmount(row.getFixedPrice(), part.getUnitPrice()))
        .findFirst()
        .orElse(rows.get(0));
  }

  private Map<String, Object> fixedSourceSnapshot(
      CostRunPartItem part, PricePrepareItem prepareItem, PriceFixedItem fixedItem, String sourceType) {
    Map<String, Object> payload = partSourceSnapshot(part, prepareItem);
    if (fixedItem != null) {
      payload.put("fixedPriceItem", mapOf(
          "id", fixedItem.getId(),
          "sourceType", fixedItem.getSourceType(),
          "sourceSystem", fixedItem.getSourceSystem(),
          "sourceName", fixedItem.getSourceName(),
          "supplierName", firstText(fixedItem.getCurrentSupplierName(), fixedItem.getSupplierName()),
          "supplierCode", fixedItem.getSupplierCode(),
          "materialCode", fixedItem.getMaterialCode(),
          "materialName", fixedItem.getMaterialName(),
          "specModel", fixedItem.getSpecModel(),
          "unit", fixedItem.getUnit(),
          "fixedPrice", fixedItem.getFixedPrice(),
          "currentTaxExcludedPrice", fixedItem.getCurrentTaxExcludedPrice(),
          "currentTaxIncludedPrice", fixedItem.getCurrentTaxIncludedPrice(),
          "plannedPrice", fixedItem.getPlannedPrice(),
          "markupRatio", fixedItem.getMarkupRatio(),
          "baseSettlePrice", fixedItem.getBaseSettlePrice(),
          "linkedSettlePrice", fixedItem.getLinkedSettlePrice(),
          "settleReferenceHeader", fixedItem.getSettleReferenceHeader(),
          "settleReferencePrice", fixedItem.getSettleReferencePrice(),
          "pricingMonth", fixedItem.getPricingMonth(),
          "effectiveFrom", stringValue(fixedItem.getEffectiveFrom()),
          "effectiveTo", stringValue(fixedItem.getEffectiveTo()),
          "processNo", fixedItem.getProcessNo(),
          "srmDocNo", fixedItem.getSrmDocNo(),
          "processStatus", fixedItem.getProcessStatus(),
          "remark", fixedItem.getRemark()));
    }
    payload.put("priceConclusion", mapOf(
        "priceKind", SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType) ? "结算固定价" : "固定采购价",
        "unitPrice", part.getUnitPrice(),
        "quantity", part.getQty(),
        "amount", part.getAmount()));
    return payload;
  }

  private Map<String, Object> fixedFormula(
      CostRunPartItem part, PriceFixedItem fixedItem, String sourceType) {
    boolean settle = SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType);
    return mapOf(
        "formula", "amount = fixedPrice * quantity",
        "displayFormula", (settle ? "结算固定价" : "固定采购价") + "金额 = 单价 × BOM 用量",
        "priceTable", "lp_price_fixed_item",
        "priceField", "fixed_price",
        "unitPrice", part.getUnitPrice(),
        "quantity", part.getQty(),
        "amount", part.getAmount(),
        "fixedPrice", fixedItem == null ? null : fixedItem.getFixedPrice(),
        "settleReferenceHeader", fixedItem == null ? null : fixedItem.getSettleReferenceHeader());
  }

  private Map<String, Object> fixedVariables(
      CostRunPartItem part, PriceFixedItem fixedItem, String sourceType) {
    return mapOf(
        "priceKind", SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType) ? "结算固定价" : "固定采购价",
        "materialCode", part.getPartCode(),
        "fixedPrice", firstNonNull(fixedItem == null ? null : fixedItem.getFixedPrice(), part.getUnitPrice()),
        "supplierName", fixedItem == null ? null : firstText(fixedItem.getCurrentSupplierName(), fixedItem.getSupplierName()),
        "effectiveFrom", fixedItem == null ? null : stringValue(fixedItem.getEffectiveFrom()),
        "effectiveTo", fixedItem == null ? null : stringValue(fixedItem.getEffectiveTo()),
        "pricingMonth", fixedItem == null ? null : fixedItem.getPricingMonth(),
        "plannedPrice", fixedItem == null ? null : fixedItem.getPlannedPrice(),
        "markupRatio", fixedItem == null ? null : fixedItem.getMarkupRatio(),
        "settleReferenceHeader", fixedItem == null ? null : fixedItem.getSettleReferenceHeader(),
        "settleReferencePrice", fixedItem == null ? null : fixedItem.getSettleReferencePrice(),
        "quantity", part.getQty(),
        "amount", part.getAmount());
  }

  private List<Map<String, Object>> fixedSteps(
      CostRunPartItem part, PriceFixedItem fixedItem, String sourceType) {
    return List.of(
        mapOf(
            "step", SOURCE_TYPE_SETTLE_FIXED_PRICE.equals(sourceType)
                ? "SETTLE_FIXED_PRICE_ROW"
                : "FIXED_PRICE_ROW",
            "formula", "fixed_price",
            "priceTable", "lp_price_fixed_item",
            "priceRowId", fixedItem == null ? null : fixedItem.getId(),
            "amount", firstNonNull(fixedItem == null ? null : fixedItem.getFixedPrice(), part.getUnitPrice())),
        mapOf(
            "step", "PART_AMOUNT",
            "formula", "unitPrice * quantity",
            "unitPrice", part.getUnitPrice(),
            "quantity", part.getQty(),
            "amount", part.getAmount()));
  }

  private PriceRangeItem loadRangePriceItem(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    if (prepareItem != null && prepareItem.getResultRefId() != null) {
      PriceRangeItem row = priceRangeItemMapper.selectById(prepareItem.getResultRefId());
      if (row != null) {
        return row;
      }
    }
    String materialCode =
        firstText(part.getPartCode(), prepareItem == null ? null : prepareItem.getMaterialCode());
    if (materialCode == null) {
      return null;
    }
    LambdaQueryWrapper<PriceRangeItem> query =
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getMaterialCode, materialCode)
            .and(q -> q.isNotNull(PriceRangeItem::getPriceInclTax)
                .or()
                .isNotNull(PriceRangeItem::getPriceExclTax));
    LocalDate priceDate = priceDate(version, prepareItem);
    if (priceDate != null) {
      query.and(q -> q.le(PriceRangeItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceRangeItem::getEffectiveFrom));
      query.and(q -> q.gt(PriceRangeItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceRangeItem::getEffectiveTo));
    }
    eqIfText(query, PriceRangeItem::getBusinessUnitType,
        firstText(part.getBusinessUnitType(), version.getBusinessUnitType()));
    List<PriceRangeItem> rows =
        priceRangeItemMapper.selectList(
            query.orderByDesc(PriceRangeItem::getEffectiveFrom)
                .orderByDesc(PriceRangeItem::getId));
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    BigDecimal qty = part.getQty() == null ? BigDecimal.ONE : part.getQty();
    return rows.stream()
        .filter(row -> matchesRange(row, qty))
        .filter(row -> sameAmount(rangeUnitPrice(row), part.getUnitPrice()))
        .findFirst()
        .orElse(rows.stream().filter(row -> matchesRange(row, qty)).findFirst().orElse(rows.get(0)));
  }

  private Map<String, Object> rangeSourceSnapshot(
      CostRunPartItem part, PricePrepareItem prepareItem, PriceRangeItem rangeItem) {
    Map<String, Object> payload = partSourceSnapshot(part, prepareItem);
    if (rangeItem != null) {
      payload.put("rangePriceItem", mapOf(
          "id", rangeItem.getId(),
          "sourceName", rangeItem.getSourceName(),
          "supplierName", rangeItem.getSupplierName(),
          "supplierCode", rangeItem.getSupplierCode(),
          "materialCode", rangeItem.getMaterialCode(),
          "materialName", rangeItem.getMaterialName(),
          "specModel", rangeItem.getSpecModel(),
          "unit", rangeItem.getUnit(),
          "rangeLow", rangeItem.getRangeLow(),
          "rangeHigh", rangeItem.getRangeHigh(),
          "priceExclTax", rangeItem.getPriceExclTax(),
          "priceInclTax", rangeItem.getPriceInclTax(),
          "taxIncluded", rangeItem.getTaxIncluded(),
          "effectiveFrom", stringValue(rangeItem.getEffectiveFrom()),
          "effectiveTo", stringValue(rangeItem.getEffectiveTo())));
    }
    payload.put("priceConclusion", mapOf(
        "priceKind", "区间价",
        "matchQuantity", part.getQty(),
        "unitPrice", part.getUnitPrice(),
        "amount", part.getAmount()));
    return payload;
  }

  private Map<String, Object> rangeFormula(CostRunPartItem part, PriceRangeItem rangeItem) {
    return mapOf(
        "formula", "amount = rangeUnitPrice * quantity",
        "displayFormula", "区间价金额 = 命中区间单价 × BOM 用量",
        "priceTable", "lp_price_range_item",
        "matchRange", rangeText(rangeItem),
        "priceField", rangeItem != null && rangeItem.getPriceInclTax() != null
            ? "price_incl_tax"
            : "price_excl_tax",
        "rangeUnitPrice", rangeUnitPrice(rangeItem),
        "quantity", part.getQty(),
        "amount", part.getAmount());
  }

  private Map<String, Object> rangeVariables(CostRunPartItem part, PriceRangeItem rangeItem) {
    return mapOf(
        "priceKind", "区间价",
        "materialCode", part.getPartCode(),
        "matchQuantity", part.getQty(),
        "rangeLow", rangeItem == null ? null : rangeItem.getRangeLow(),
        "rangeHigh", rangeItem == null ? null : rangeItem.getRangeHigh(),
        "priceExclTax", rangeItem == null ? null : rangeItem.getPriceExclTax(),
        "priceInclTax", rangeItem == null ? null : rangeItem.getPriceInclTax(),
        "rangeUnitPrice", firstNonNull(rangeUnitPrice(rangeItem), part.getUnitPrice()),
        "quantity", part.getQty(),
        "amount", part.getAmount());
  }

  private List<Map<String, Object>> rangeSteps(CostRunPartItem part, PriceRangeItem rangeItem) {
    return List.of(
        mapOf(
            "step", "RANGE_PRICE_ROW",
            "formula", "rangeLow <= quantity <= rangeHigh",
            "priceTable", "lp_price_range_item",
            "priceRowId", rangeItem == null ? null : rangeItem.getId(),
            "matchQuantity", part.getQty(),
            "matchRange", rangeText(rangeItem),
            "amount", firstNonNull(rangeUnitPrice(rangeItem), part.getUnitPrice())),
        mapOf(
            "step", "PART_AMOUNT",
            "formula", "unitPrice * quantity",
            "unitPrice", part.getUnitPrice(),
            "quantity", part.getQty(),
            "amount", part.getAmount()));
  }

  private PriceLinkedCalcItem loadLinkedCalc(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    if (prepareItem != null && prepareItem.getResultRefId() != null) {
      PriceLinkedCalcItem calc = priceLinkedCalcItemMapper.selectById(prepareItem.getResultRefId());
      if (calc != null) {
        return calc;
      }
    }
    String itemCode =
        firstText(part.getPartCode(), prepareItem == null ? null : prepareItem.getMaterialCode());
    String pricingMonth =
        firstText(version.getPricingMonth(), prepareItem == null ? null : prepareItem.getPeriodMonth());
    if (itemCode == null) {
      return null;
    }
    LambdaQueryWrapper<PriceLinkedCalcItem> query =
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getItemCode, itemCode)
            .eq(StringUtils.hasText(pricingMonth), PriceLinkedCalcItem::getPricingMonth, pricingMonth)
            .eq(PriceLinkedCalcItem::getCalcStatus, "OK")
            .isNotNull(PriceLinkedCalcItem::getPartUnitPrice);
    String oaNo = firstText(part.getOaNo(), version.getOaNo());
    String businessUnitType = firstText(part.getBusinessUnitType(), version.getBusinessUnitType());
    if (oaNo != null) {
      query.eq(PriceLinkedCalcItem::getOaNo, oaNo);
      query.eq(PriceLinkedCalcItem::getCalcScene, "QUOTE");
    } else if (businessUnitType != null) {
      query.eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType);
      query.eq(PriceLinkedCalcItem::getCalcScene, "MONTHLY_ADJUST");
    } else {
      return null;
    }
    List<PriceLinkedCalcItem> rows =
        priceLinkedCalcItemMapper.selectList(
            query.orderByDesc(PriceLinkedCalcItem::getUpdatedAt)
                .orderByDesc(PriceLinkedCalcItem::getId)
                .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private PriceLinkedItem loadLinkedItem(
      QuoteCostRunVersion version,
      CostRunPartItem part,
      PricePrepareItem prepareItem,
      PriceLinkedCalcItem linkedCalc) {
    String materialCode =
        firstText(
            linkedCalc == null ? null : linkedCalc.getItemCode(),
            part.getPartCode(),
            prepareItem == null ? null : prepareItem.getMaterialCode());
    String pricingMonth =
        firstText(
            linkedCalc == null ? null : linkedCalc.getPricingMonth(),
            version.getPricingMonth(),
            prepareItem == null ? null : prepareItem.getPeriodMonth());
    if (materialCode == null || pricingMonth == null) {
      return null;
    }
    LambdaQueryWrapper<PriceLinkedItem> query =
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getMaterialCode, materialCode)
            .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
            .eq(PriceLinkedItem::getDeleted, 0);
    String businessUnitType =
        firstText(
            linkedCalc == null ? null : linkedCalc.getBusinessUnitType(),
            part.getBusinessUnitType(),
            version.getBusinessUnitType());
    if (businessUnitType != null) {
      query.eq(PriceLinkedItem::getBusinessUnitType, businessUnitType);
    }
    List<PriceLinkedItem> rows =
        priceLinkedItemMapper.selectList(
            query.orderByDesc(PriceLinkedItem::getEffectiveFrom)
                .orderByDesc(PriceLinkedItem::getId)
                .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private Map<String, Object> linkedSourceSnapshot(
      CostRunPartItem part,
      PricePrepareItem prepareItem,
      PriceLinkedCalcItem linkedCalc,
      PriceLinkedItem linkedItem,
      Map<String, Object> trace) {
    Map<String, Object> payload = partSourceSnapshot(part, prepareItem);
    if (linkedCalc != null) {
      payload.put("linkedCalcItem", mapOf(
          "id", linkedCalc.getId(),
          "oaNo", linkedCalc.getOaNo(),
          "itemCode", linkedCalc.getItemCode(),
          "shapeAttr", linkedCalc.getShapeAttr(),
          "bomQty", linkedCalc.getBomQty(),
          "partUnitPrice", linkedCalc.getPartUnitPrice(),
          "partAmount", linkedCalc.getPartAmount(),
          "businessUnitType", linkedCalc.getBusinessUnitType(),
          "calcScene", linkedCalc.getCalcScene(),
          "pricingMonth", linkedCalc.getPricingMonth(),
          "adjustBatchId", linkedCalc.getAdjustBatchId(),
          "factorSource", linkedCalc.getFactorSource(),
          "calcFingerprint", linkedCalc.getCalcFingerprint(),
          "calcStatus", linkedCalc.getCalcStatus(),
          "calcMessage", linkedCalc.getCalcMessage(),
          "createdAt", stringValue(linkedCalc.getCreatedAt()),
          "updatedAt", stringValue(linkedCalc.getUpdatedAt())));
    }
    if (linkedItem != null) {
      payload.put("linkedItem", mapOf(
          "id", linkedItem.getId(),
          "pricingMonth", linkedItem.getPricingMonth(),
          "businessUnitType", linkedItem.getBusinessUnitType(),
          "orgCode", linkedItem.getOrgCode(),
          "sourceName", linkedItem.getSourceName(),
          "supplierName", linkedItem.getSupplierName(),
          "supplierCode", linkedItem.getSupplierCode(),
          "purchaseClass", linkedItem.getPurchaseClass(),
          "materialName", linkedItem.getMaterialName(),
          "materialCode", linkedItem.getMaterialCode(),
          "specModel", linkedItem.getSpecModel(),
          "unit", linkedItem.getUnit(),
          "formulaExpr", linkedItem.getFormulaExpr(),
          "formulaExprCn", linkedItem.getFormulaExprCn(),
          "blankWeight", linkedItem.getBlankWeight(),
          "netWeight", linkedItem.getNetWeight(),
          "processFee", linkedItem.getProcessFee(),
          "agentFee", linkedItem.getAgentFee(),
          "manualPrice", linkedItem.getManualPrice(),
          "taxIncluded", linkedItem.getTaxIncluded(),
          "effectiveFrom", stringValue(linkedItem.getEffectiveFrom()),
          "effectiveTo", stringValue(linkedItem.getEffectiveTo()),
          "orderType", linkedItem.getOrderType(),
          "quota", linkedItem.getQuota(),
          "createdAt", stringValue(linkedItem.getCreatedAt()),
          "updatedAt", stringValue(linkedItem.getUpdatedAt())));
    }
    if (!trace.isEmpty()) {
      payload.put("linkedTrace", trace);
    }
    return payload;
  }

  private Map<String, Object> linkedFormula(
      CostRunPartItem part,
      PriceLinkedCalcItem linkedCalc,
      PriceLinkedItem linkedItem,
      Map<String, Object> trace) {
    String normalizedExpr =
        firstText(
            textValue(trace.get("normalizedExpr")),
            linkedItem == null ? null : linkedItem.getFormulaExpr(),
            textValue(trace.get("rawExpr")));
    String rawExpr =
        firstText(
            textValue(trace.get("rawExpr")),
            linkedItem == null ? null : linkedItem.getFormulaExpr(),
            normalizedExpr);
    String formulaText =
        firstText(
            linkedItem == null ? null : linkedItem.getFormulaExprCn(),
            renderLinkedFormulaText(normalizedExpr, trace),
            rawExpr);
    return mapOf(
        "formula", normalizedExpr,
        "rawFormula", rawExpr,
        "formulaText", formulaText,
        "displayFormula", formulaText,
        "evalResult", trace.get("result"),
        "manualPrice", trace.get("manualPrice"),
        "manualPriceMonth", trace.get("manualPriceMonth"),
        "finalUnitPrice", firstNonNull(
            linkedCalc == null ? null : linkedCalc.getPartUnitPrice(),
            part.getUnitPrice()),
        "quantity", firstNonNull(linkedCalc == null ? null : linkedCalc.getBomQty(), part.getQty()),
        "amount", firstNonNull(linkedCalc == null ? null : linkedCalc.getPartAmount(), part.getAmount()));
  }

  private Map<String, Object> linkedVariables(
      CostRunPartItem part,
      PriceLinkedCalcItem linkedCalc,
      PriceLinkedItem linkedItem,
      Map<String, Object> trace) {
    return mapOf(
        "variables", linkedVariableRows(trace),
        "evalResult", trace.get("result"),
        "finalUnitPrice", firstNonNull(
            linkedCalc == null ? null : linkedCalc.getPartUnitPrice(),
            part.getUnitPrice()),
        "quantity", firstNonNull(linkedCalc == null ? null : linkedCalc.getBomQty(), part.getQty()),
        "amount", firstNonNull(linkedCalc == null ? null : linkedCalc.getPartAmount(), part.getAmount()),
        "blankWeight", linkedItem == null ? null : linkedItem.getBlankWeight(),
        "netWeight", linkedItem == null ? null : linkedItem.getNetWeight(),
        "processFee", linkedItem == null ? null : linkedItem.getProcessFee(),
        "agentFee", linkedItem == null ? null : linkedItem.getAgentFee(),
        "manualPrice", linkedItem == null ? null : linkedItem.getManualPrice(),
        "calcStatus", linkedCalc == null ? null : linkedCalc.getCalcStatus(),
        "calcMessage", linkedCalc == null ? null : linkedCalc.getCalcMessage(),
        "error", trace.get("error"));
  }

  private List<Map<String, Object>> linkedSteps(
      CostRunPartItem part,
      PriceLinkedCalcItem linkedCalc,
      PriceLinkedItem linkedItem,
      Map<String, Object> trace) {
    String normalizedExpr =
        firstText(
            textValue(trace.get("normalizedExpr")),
            linkedItem == null ? null : linkedItem.getFormulaExpr(),
            textValue(trace.get("rawExpr")));
    List<Map<String, Object>> steps = new ArrayList<>();
    steps.add(mapOf(
        "step", "LINKED_FORMULA",
        "rawFormula", firstText(textValue(trace.get("rawExpr")), linkedItem == null ? null : linkedItem.getFormulaExpr()),
        "formula", normalizedExpr,
        "formulaText", firstText(
            linkedItem == null ? null : linkedItem.getFormulaExprCn(),
            renderLinkedFormulaText(normalizedExpr, trace))));
    steps.add(mapOf(
        "step", "LINKED_VARIABLES",
        "variables", linkedVariableRows(trace)));
    steps.add(mapOf(
        "step", "LINKED_EVALUATE",
        "formula", normalizedExpr,
        "result", trace.get("result"),
        "error", trace.get("error")));
    steps.add(mapOf(
        "step", "PART_AMOUNT",
        "formula", "finalUnitPrice * quantity",
        "finalUnitPrice", firstNonNull(
            linkedCalc == null ? null : linkedCalc.getPartUnitPrice(),
            part.getUnitPrice()),
        "quantity", firstNonNull(linkedCalc == null ? null : linkedCalc.getBomQty(), part.getQty()),
        "amount", firstNonNull(linkedCalc == null ? null : linkedCalc.getPartAmount(), part.getAmount())));
    return steps;
  }

  private Map<String, Object> linkedTraceMap(PriceLinkedCalcItem linkedCalc) {
    if (linkedCalc == null || !StringUtils.hasText(linkedCalc.getTraceJson())) {
      return Map.of();
    }
    try {
      Object parsed = objectMapper.readValue(linkedCalc.getTraceJson(), Object.class);
      if (parsed instanceof Map<?, ?> map) {
        Map<String, Object> trace = new LinkedHashMap<>();
        map.forEach((key, value) -> trace.put(String.valueOf(key), value));
        return trace;
      }
      return mapOf("rawTraceJson", linkedCalc.getTraceJson());
    } catch (JsonProcessingException ex) {
      return mapOf(
          "rawTraceJson", linkedCalc.getTraceJson(),
          "parseError", ex.getMessage());
    }
  }

  private List<Map<String, Object>> linkedVariableRows(Map<String, Object> trace) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Object detailObj = trace.get("variableDetails");
    if (detailObj instanceof List<?> details) {
      for (Object detail : details) {
        if (detail instanceof Map<?, ?> map) {
          Object code = map.get("code");
          if (code != null) {
            rows.add(mapOf(
                "name", code,
                "label", firstText(textValue(map.get("name")), textValue(code)),
                "value", map.get("value"),
                "source", map.get("source")));
          }
        }
      }
    }
    if (!rows.isEmpty()) {
      return rows;
    }
    Object variablesObj = trace.get("variables");
    if (variablesObj instanceof Map<?, ?> variables) {
      for (Map.Entry<?, ?> entry : variables.entrySet()) {
        if (entry.getKey() != null) {
          rows.add(mapOf(
              "name", entry.getKey(),
              "label", entry.getKey(),
              "value", entry.getValue()));
        }
      }
    }
    return rows;
  }

  private String renderLinkedFormulaText(String formula, Map<String, Object> trace) {
    if (!StringUtils.hasText(formula)) {
      return null;
    }
    Map<String, String> labels = linkedVariableLabels(trace);
    Matcher matcher = VARIABLE_TOKEN.matcher(formula);
    StringBuffer buffer = new StringBuffer();
    boolean replaced = false;
    while (matcher.find()) {
      String code = matcher.group(1);
      String label = labels.get(code);
      if (StringUtils.hasText(label)) {
        matcher.appendReplacement(buffer, Matcher.quoteReplacement(label));
        replaced = true;
      }
    }
    matcher.appendTail(buffer);
    return replaced ? buffer.toString() : null;
  }

  private Map<String, String> linkedVariableLabels(Map<String, Object> trace) {
    Map<String, String> labels = new LinkedHashMap<>();
    Object detailObj = trace.get("variableDetails");
    if (detailObj instanceof List<?> details) {
      for (Object detail : details) {
        if (detail instanceof Map<?, ?> map) {
          String code = textValue(map.get("code"));
          String name = textValue(map.get("name"));
          if (code != null && name != null) {
            labels.put(code, name);
          }
        }
      }
    }
    return labels;
  }

  private Map<String, Object> makePartSourceSnapshot(
      CostRunPartItem part, PricePrepareItem prepareItem, List<MakePartPriceCalcRow> rows) {
    Map<String, Object> payload = partSourceSnapshot(part, prepareItem);
    payload.put("makePartCalc", mapOf(
        "calcBatchId", firstText(rows.stream().map(MakePartPriceCalcRow::getCalcBatchId).toArray(String[]::new)),
        "rowCount", rows.size(),
        "parentMaterialNo", firstText(
            rows.stream().map(MakePartPriceCalcRow::getParentMaterialNo).toArray(String[]::new)),
        "parentMaterialName", firstText(
            rows.stream().map(MakePartPriceCalcRow::getParentMaterialName).toArray(String[]::new)),
        "pricingMonth", firstText(
            rows.stream().map(MakePartPriceCalcRow::getPricingMonth).toArray(String[]::new))));
    return payload;
  }

  private Map<String, Object> makePartAggregateFormula(CostRunPartItem part) {
    return mapOf(
        "formula", "partAmount = makePartFinalUnitPrice * bomQuantity; makePartFinalUnitPrice = sum(makePartRow.costPrice)",
        "displayFormula", "部品价格 = 自制件最终单价 × BOM 用量；自制件最终单价 = Σ 制造件生成明细行成本",
        "amountFormula", "amount = unitPrice * quantity",
        "finalUnitPriceFormula", "unitPrice = sum(children.costPrice)",
        "priceSource", part.getPriceSource());
  }

  private Map<String, Object> makePartAggregateVariables(
      CostRunPartItem part, PricePrepareItem prepareItem, List<MakePartPriceCalcRow> rows) {
    return mapOf(
        "makePartFinalUnitPrice", part.getUnitPrice(),
        "bomQuantity", part.getQty(),
        "amount", part.getAmount(),
        "pricePrepareUnitPrice", prepareItem == null ? null : prepareItem.getUnitPrice(),
        "pricePrepareAmount", prepareItem == null ? null : prepareItem.getAmount(),
        "calcBatchId", firstText(rows.stream().map(MakePartPriceCalcRow::getCalcBatchId).toArray(String[]::new)),
        "rowCount", rows.size(),
        "rowCostSum", sum(rows.stream().map(MakePartPriceCalcRow::getCostPrice).toList()),
        "parentTotalCostPrice", firstNonNull(
            rows.stream()
                .map(MakePartPriceCalcRow::getParentTotalCostPrice)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null),
            part.getUnitPrice()),
        "makePartRows", rows.stream().map(this::makePartVariables).toList(),
        "remark", firstText(part.getRemark(), prepareItem == null ? null : prepareItem.getMessage()));
  }

  private List<Map<String, Object>> makePartAggregateSteps(
      CostRunPartItem part, List<MakePartPriceCalcRow> rows) {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (MakePartPriceCalcRow row : rows) {
      steps.add(mapOf(
          "step", "MAKE_PART_ROW",
          "childMaterialNo", row.getChildMaterialNo(),
          "childMaterialName", row.getChildMaterialName(),
          "itemProcessType", row.getItemProcessType(),
          "formula", makePartFormula(row).get("formula"),
          "displayFormula", makePartFormula(row).get("displayFormula"),
          "variables", makePartVariables(row),
          "amount", row.getCostPrice()));
    }
    steps.add(mapOf(
        "step", "MAKE_PART_FINAL_UNIT_PRICE",
        "formula", "sum(children.costPrice)",
        "amount", firstNonNull(
            rows.stream()
                .map(MakePartPriceCalcRow::getParentTotalCostPrice)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null),
            part.getUnitPrice())));
    steps.add(mapOf(
        "step", "PART_AMOUNT",
        "formula", "makePartFinalUnitPrice * bomQuantity",
        "makePartFinalUnitPrice", part.getUnitPrice(),
        "bomQuantity", part.getQty(),
        "amount", part.getAmount()));
    return steps;
  }

  private List<Map<String, Object>> makePartChildren(
      List<MakePartPriceCalcRow> rows, Long pricePrepareItemId) {
    if (rows.isEmpty()) {
      return List.of(pendingDetail("未定位到制造件价格生成明细", pricePrepareItemId));
    }
    return rows.stream()
        .filter(Objects::nonNull)
        .map(this::makePartChild)
        .toList();
  }

  private List<MakePartPriceCalcRow> loadMakePartCalcRows(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    MakePartPriceCalcRow anchor = null;
    if (prepareItem != null && prepareItem.getResultRefId() != null) {
      anchor = makePartPriceCalcRowMapper.selectById(prepareItem.getResultRefId());
    }
    if (anchor == null) {
      anchor = selectLatestMakePartAnchor(version, part, prepareItem);
    }
    if (anchor == null) {
      return List.of();
    }
    String parentMaterialNo =
        firstText(
            anchor.getParentMaterialNo(),
            part.getPartCode(),
            prepareItem == null ? null : prepareItem.getMaterialCode());
    String calcBatchId = trimToNull(anchor.getCalcBatchId());
    if (calcBatchId != null && parentMaterialNo != null) {
      List<MakePartPriceCalcRow> rows =
          makePartPriceCalcRowMapper.selectList(
              Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
                  .eq(MakePartPriceCalcRow::getCalcBatchId, calcBatchId)
                  .eq(MakePartPriceCalcRow::getParentMaterialNo, parentMaterialNo)
                  .orderByAsc(MakePartPriceCalcRow::getChildMaterialNo)
                  .orderByAsc(MakePartPriceCalcRow::getScrapCode)
                  .orderByAsc(MakePartPriceCalcRow::getId));
      return rows == null || rows.isEmpty() ? List.of(anchor) : rows;
    }
    List<MakePartPriceCalcRow> rows = selectMakePartRowsByBusinessKey(version, part, prepareItem);
    return rows.isEmpty() ? List.of(anchor) : rows;
  }

  private MakePartPriceCalcRow selectLatestMakePartAnchor(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    LambdaQueryWrapper<MakePartPriceCalcRow> query = makePartBusinessKeyQuery(version, part, prepareItem);
    if (query == null) {
      return null;
    }
    List<MakePartPriceCalcRow> rows =
        makePartPriceCalcRowMapper.selectList(
            query
                .orderByDesc(MakePartPriceCalcRow::getCreatedAt)
                .orderByDesc(MakePartPriceCalcRow::getId)
                .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private List<MakePartPriceCalcRow> selectMakePartRowsByBusinessKey(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    LambdaQueryWrapper<MakePartPriceCalcRow> query = makePartBusinessKeyQuery(version, part, prepareItem);
    if (query == null) {
      return List.of();
    }
    List<MakePartPriceCalcRow> rows =
        makePartPriceCalcRowMapper.selectList(
            query
                .orderByAsc(MakePartPriceCalcRow::getChildMaterialNo)
                .orderByAsc(MakePartPriceCalcRow::getScrapCode)
                .orderByAsc(MakePartPriceCalcRow::getId));
    return rows == null ? List.of() : rows;
  }

  private LambdaQueryWrapper<MakePartPriceCalcRow> makePartBusinessKeyQuery(
      QuoteCostRunVersion version, CostRunPartItem part, PricePrepareItem prepareItem) {
    String parentMaterialNo =
        firstText(part.getPartCode(), prepareItem == null ? null : prepareItem.getMaterialCode());
    String oaNo = firstText(part.getOaNo(), version.getOaNo());
    String pricingMonth =
        firstText(version.getPricingMonth(), prepareItem == null ? null : prepareItem.getPeriodMonth());
    String businessUnitType = firstText(part.getBusinessUnitType(), version.getBusinessUnitType());
    if (parentMaterialNo == null || oaNo == null || pricingMonth == null || businessUnitType == null) {
      return null;
    }
    LambdaQueryWrapper<MakePartPriceCalcRow> query = Wrappers.lambdaQuery(MakePartPriceCalcRow.class);
    eqIfText(query, MakePartPriceCalcRow::getParentMaterialNo, parentMaterialNo);
    eqIfText(query, MakePartPriceCalcRow::getOaNo, oaNo);
    eqIfText(query, MakePartPriceCalcRow::getPricingMonth, pricingMonth);
    eqIfText(query, MakePartPriceCalcRow::getBusinessUnitType, businessUnitType);
    return query;
  }

  private Map<String, Object> makePartChild(MakePartPriceCalcRow row) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("calcRowId", row.getId());
    payload.put("calcBatchId", row.getCalcBatchId());
    payload.put("oaNo", row.getOaNo());
    payload.put("pricingMonth", row.getPricingMonth());
    payload.put("businessUnitType", row.getBusinessUnitType());
    payload.put("parentMaterialNo", row.getParentMaterialNo());
    payload.put("parentMaterialName", row.getParentMaterialName());
    payload.put("drawingNo", row.getDrawingNo());
    payload.put("childMaterialNo", row.getChildMaterialNo());
    payload.put("childMaterialName", row.getChildMaterialName());
    payload.put("childMaterialSpec", row.getChildMaterialSpec());
    payload.put("itemProcessType", row.getItemProcessType());
    payload.put("stockUnit", row.getStockUnit());
    payload.put("qtyPerParent", row.getQtyPerParent());
    payload.put("grossWeightG", row.getGrossWeightG());
    payload.put("netWeightG", row.getNetWeightG());
    payload.put("rawPriceType", row.getRawPriceType());
    payload.put("rawUnitPrice", row.getRawUnitPrice());
    payload.put("scrapCode", row.getScrapCode());
    payload.put("scrapName", row.getScrapName());
    payload.put("scrapPriceType", row.getScrapPriceType());
    payload.put("scrapUnitPrice", row.getScrapUnitPrice());
    payload.put("noScrapConfirmed", row.getNoScrapConfirmed());
    payload.put("noScrapConfirmationId", row.getNoScrapConfirmationId());
    payload.put("outsourceFee", row.getOutsourceFee());
    payload.put("costPrice", row.getCostPrice());
    payload.put("parentTotalCostPrice", row.getParentTotalCostPrice());
    payload.put("priceComplete", row.getPriceComplete());
    payload.put("status", row.getStatus());
    payload.put("remark", row.getRemark());
    payload.put("formula", makePartFormula(row));
    payload.put("variables", makePartVariables(row));
    payload.put("steps", makePartSteps(row));
    return payload;
  }

  private Map<String, Object> makePartFormula(MakePartPriceCalcRow row) {
    if (PROCESS_TYPE_BLANK.equals(row.getItemProcessType())) {
      return mapOf(
          "formula", "rawUnitPrice - ((grossWeightG - netWeightG) / 1000) * scrapUnitPrice",
          "displayFormula", "明细行成本 = 毛坯单价 - 废料重(kg) × 废料价",
          "unitNote", "重量字段为 g，参与元/kg 价格计算前除以 1000");
    }
    return mapOf(
        "formula",
        "(grossWeightG / 1000) * rawUnitPrice - ((grossWeightG - netWeightG) / 1000) * scrapUnitPrice",
        "displayFormula", "明细行成本 = 毛重(kg) × 原材料价 - 废料重(kg) × 废料价",
        "unitNote", "重量字段为 g，参与元/kg 价格计算前除以 1000");
  }

  private Map<String, Object> makePartVariables(MakePartPriceCalcRow row) {
    return mapOf(
        "itemProcessType", row.getItemProcessType(),
        "grossWeightG", row.getGrossWeightG(),
        "netWeightG", row.getNetWeightG(),
        "scrapWeightKg", scrapWeightKg(row),
        "rawUnitPrice", row.getRawUnitPrice(),
        "scrapUnitPrice", row.getScrapUnitPrice(),
        "outsourceFee", row.getOutsourceFee(),
        "costPrice", row.getCostPrice(),
        "parentTotalCostPrice", row.getParentTotalCostPrice());
  }

  private List<Map<String, Object>> makePartSteps(MakePartPriceCalcRow row) {
    BigDecimal grossWeightKg = toKg(row.getGrossWeightG());
    BigDecimal scrapWeightKg = scrapWeightKg(row);
    BigDecimal rawAmount = rawAmount(row, grossWeightKg);
    BigDecimal scrapDeduction = multiply(scrapWeightKg, row.getScrapUnitPrice());
    return List.of(
        mapOf(
            "step", "WEIGHT_CONVERT",
            "grossWeightG", row.getGrossWeightG(),
            "netWeightG", row.getNetWeightG(),
            "grossWeightKg", grossWeightKg,
            "scrapWeightKg", scrapWeightKg),
        mapOf(
            "step", PROCESS_TYPE_BLANK.equals(row.getItemProcessType()) ? "BLANK_AMOUNT" : "RAW_MATERIAL_AMOUNT",
            "formula", PROCESS_TYPE_BLANK.equals(row.getItemProcessType())
                ? "rawUnitPrice"
                : "(grossWeightG / 1000) * rawUnitPrice",
            "grossWeightKg", grossWeightKg,
            "rawUnitPrice", row.getRawUnitPrice(),
            "amount", rawAmount),
        mapOf(
            "step", "SCRAP_DEDUCTION",
            "formula", "scrapWeightKg * scrapUnitPrice",
            "scrapWeightKg", scrapWeightKg,
            "scrapUnitPrice", row.getScrapUnitPrice(),
            "amount", scrapDeduction),
        mapOf(
            "step", "CHILD_COST_PRICE",
            "formula", PROCESS_TYPE_BLANK.equals(row.getItemProcessType())
                ? "rawUnitPrice - scrapDeduction"
                : "rawMaterialAmount - scrapDeduction",
            "rawAmount", rawAmount,
            "scrapDeduction", scrapDeduction,
            "amount", row.getCostPrice()));
  }

  private BigDecimal rawAmount(MakePartPriceCalcRow row, BigDecimal grossWeightKg) {
    if (PROCESS_TYPE_BLANK.equals(row.getItemProcessType())) {
      return row.getRawUnitPrice();
    }
    return multiply(grossWeightKg, row.getRawUnitPrice());
  }

  private BigDecimal scrapWeightKg(MakePartPriceCalcRow row) {
    if (row.getGrossWeightG() == null || row.getNetWeightG() == null) {
      return null;
    }
    return row.getGrossWeightG()
        .subtract(row.getNetWeightG())
        .divide(G_TO_KG, AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal toKg(BigDecimal grams) {
    return grams == null ? null : grams.divide(G_TO_KG, AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal multiply(BigDecimal left, BigDecimal right) {
    return left == null || right == null ? null : left.multiply(right);
  }

  private BigDecimal sum(List<BigDecimal> values) {
    BigDecimal result = BigDecimal.ZERO;
    boolean hasValue = false;
    for (BigDecimal value : values) {
      if (value != null) {
        result = result.add(value);
        hasValue = true;
      }
    }
    return hasValue ? result : null;
  }

  private Map<String, Object> costSourceSnapshot(CostRunCostItem cost) {
    String costCode = trimToNull(cost.getCostCode());
    return mapOf(
        "id", cost.getId(),
        "costRunNo", cost.getCostRunNo(),
        "lineNo", cost.getLineNo(),
        "costCode", costCode,
        "costName", cost.getCostName(),
        "baseAmount", cost.getBaseAmount(),
        "rate", cost.getRate(),
        "amount", cost.getAmount(),
        "sourceTable", cost.getSourceTable(),
        "sourceId", cost.getSourceId(),
        "sourceType", costSourceType(costCode),
        "sourceDescription", costSourceDescription(costCode),
        "category", cost.getCategory(),
        "remark", cost.getRemark());
  }

  private Map<String, Object> costFormula(String costCode) {
    String formula;
    String display;
    if (COST_MATERIAL.equals(costCode)) {
      formula = "sum(part.amount) + sum(aux.amount) + departmentFees + packageAmount";
      display = "材料费 = 部品金额 + 辅料 + 部门经费 + 包装";
    } else if (COST_DIRECT_LABOR.equals(costCode)) {
      formula = "cms.directLaborAmount";
      display = "直接人工工资 = CMS直接人工有效来源金额";
    } else if (COST_INDIRECT_LABOR.equals(costCode)) {
      formula = "cms.indirectLaborAmount";
      display = "辅助人工工资 = CMS辅助人工有效来源金额";
    } else if (COST_LOSS.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "净损失 = 损失基数 × 净损失率";
    } else if (COST_MANUFACTURE.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "制造费用 = 制造成本 × 制造费用率";
    } else if (COST_MANUFACTURE_COST.equals(costCode)) {
      formula = "MATERIAL + DIRECT_LABOR + INDIRECT_LABOR + LOSS + MANUFACTURE";
      display = "制造成本 = 材料费 + 直接人工 + 辅助人工 + 净损失 + 制造费用";
    } else if (COST_MGMT_EXP.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "管理费用 = 调整后制造成本 × 管理费率";
    } else if (COST_SALES_EXP.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "营业费用 = 调整后制造成本 × 营业费率";
    } else if (COST_FIN_EXP.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "财务费用 = 调整后制造成本 × 财务费率";
    } else if (COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "调整后制造成本 = 制造成本 × 产品属性系数";
    } else if (COST_OVERHAUL.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "大修费 = CMS部门经费基数 × 大修费率";
    } else if (COST_TOOLING_REPAIR.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "工装零星修理费 = CMS部门经费基数 × 工装零星修理费率";
    } else if (COST_WATER_POWER.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "水电费 = CMS部门经费基数 × 水电费率";
    } else if (COST_DEPT_OTHER.equals(costCode)) {
      formula = "baseAmount * rate";
      display = "其他费用 = CMS部门经费基数 × 其他费用率";
    } else if (COST_TOTAL.equals(costCode)) {
      formula = "ADJUSTED_MANUFACTURE_COST + MGMT_EXP + SALES_EXP + FIN_EXP + sum(otherExpense.amount)";
      display = "不含税总成本 = 调整后制造成本 + 三项费用 + 其他费用";
    } else {
      formula = "snapshot.amount";
      display = "金额 = 核算结果快照金额";
    }
    return mapOf("costCode", costCode, "formula", formula, "displayFormula", display);
  }

  private Map<String, Object> costVariables(CostRunCostItem cost) {
    return mapOf(
        "costCode", trimToNull(cost.getCostCode()),
        "costName", trimToNull(cost.getCostName()),
        "baseAmount", cost.getBaseAmount(),
        "rate", cost.getRate(),
        "amount", cost.getAmount(),
        "sourceTable", cost.getSourceTable(),
        "sourceId", cost.getSourceId(),
        "sourceDescription", costSourceDescription(trimToNull(cost.getCostCode())),
        "remark", cost.getRemark());
  }

  private List<Map<String, Object>> costSteps(CostRunCostItem cost, String costCode) {
    Map<String, Object> formula = costFormula(costCode);
    return List.of(mapOf(
        "step", costCode == null ? "COST_ITEM" : costCode,
        "formula", formula.get("formula"),
        "displayFormula", formula.get("displayFormula"),
        "baseAmount", cost.getBaseAmount(),
        "rate", cost.getRate(),
        "amount", cost.getAmount(),
        "sourceTable", cost.getSourceTable(),
        "sourceId", cost.getSourceId(),
        "sourceDescription", costSourceDescription(costCode),
        "remark", cost.getRemark()));
  }

  private boolean costHasChildren(String costCode) {
    return COST_MATERIAL.equals(costCode)
        || COST_MANUFACTURE_COST.equals(costCode)
        || COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)
        || COST_TOTAL.equals(costCode);
  }

  private List<Map<String, Object>> costChildren(String costCode, List<CostRunCostItem> costs) {
    if (COST_MATERIAL.equals(costCode)) {
      return costs.stream()
          .filter(Objects::nonNull)
          .filter(item -> materialChildCost(trimToNull(item.getCostCode())))
          .map(item -> costChild(item, true, "MATERIAL_COMPONENT"))
          .toList();
    }
    if (COST_MANUFACTURE_COST.equals(costCode)) {
      return costChildrenByCodes(
          costs,
          List.of(
              COST_MATERIAL,
              COST_DIRECT_LABOR,
              COST_INDIRECT_LABOR,
              COST_LOSS,
              COST_MANUFACTURE),
          "MANUFACTURE_COST_COMPONENT");
    }
    if (COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)) {
      return costChildrenByCodes(costs, List.of(COST_MANUFACTURE_COST), "ADJUSTED_BASE");
    }
    if (COST_TOTAL.equals(costCode)) {
      return costs.stream()
          .filter(Objects::nonNull)
          .filter(item -> !COST_TOTAL.equals(trimToNull(item.getCostCode())))
          .map(item -> costChild(item, totalDirectChild(trimToNull(item.getCostCode())), "TOTAL_COMPONENT"))
          .toList();
    }
    return List.of();
  }

  private List<Map<String, Object>> costChildrenByCodes(
      List<CostRunCostItem> costs, List<String> costCodes, String role) {
    List<Map<String, Object>> children = new ArrayList<>();
    for (String costCode : costCodes) {
      CostRunCostItem item = findCost(costs, costCode);
      if (item != null) {
        children.add(costChild(item, true, role));
      }
    }
    return children;
  }

  private CostRunCostItem findCost(List<CostRunCostItem> costs, String costCode) {
    for (CostRunCostItem item : costs) {
      if (item != null && costCode.equals(trimToNull(item.getCostCode()))) {
        return item;
      }
    }
    return null;
  }

  private boolean materialChildCost(String costCode) {
    return startsWith(costCode, "AUX_")
        || COST_OVERHAUL.equals(costCode)
        || COST_TOOLING_REPAIR.equals(costCode)
        || COST_WATER_POWER.equals(costCode)
        || COST_DEPT_OTHER.equals(costCode)
        || COST_OTHER_EXP_PACKAGE.equals(costCode);
  }

  private boolean totalDirectChild(String costCode) {
    return COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)
        || COST_MGMT_EXP.equals(costCode)
        || COST_SALES_EXP.equals(costCode)
        || COST_FIN_EXP.equals(costCode)
        || (startsWith(costCode, "OTHER_EXP_") && !COST_OTHER_EXP_PACKAGE.equals(costCode));
  }

  private Map<String, Object> costChild(CostRunCostItem item, boolean included, String role) {
    return mapOf(
        "costItemId", item.getId(),
        "costCode", item.getCostCode(),
        "costName", item.getCostName(),
        "baseAmount", item.getBaseAmount(),
        "rate", item.getRate(),
        "amount", item.getAmount(),
        "sourceTable", item.getSourceTable(),
        "sourceId", item.getSourceId(),
        "category", item.getCategory(),
        "role", role,
        "included", included,
        "remark", item.getRemark());
  }

  private String costSourceDescription(String costCode) {
    if (COST_DIRECT_LABOR.equals(costCode)) {
      return "CMS直接人工工资有效来源";
    }
    if (COST_INDIRECT_LABOR.equals(costCode)) {
      return "CMS辅助人工工资有效来源";
    }
    if (COST_OVERHAUL.equals(costCode)
        || COST_TOOLING_REPAIR.equals(costCode)
        || COST_WATER_POWER.equals(costCode)
        || COST_DEPT_OTHER.equals(costCode)) {
      return "CMS部门经费率有效来源";
    }
    if (COST_LOSS.equals(costCode)) {
      return "报价净损失率配置";
    }
    if (COST_MANUFACTURE.equals(costCode)) {
      return "制造费用率配置";
    }
    if (COST_MGMT_EXP.equals(costCode)
        || COST_SALES_EXP.equals(costCode)
        || COST_FIN_EXP.equals(costCode)) {
      return "三项费用率配置";
    }
    if (COST_ADJUSTED_MANUFACTURE_COST.equals(costCode)) {
      return "产品属性系数配置";
    }
    if (COST_MANUFACTURE_COST.equals(costCode)
        || COST_MATERIAL.equals(costCode)
        || COST_TOTAL.equals(costCode)) {
      return "成本核算汇总项";
    }
    if (startsWith(costCode, "OTHER_EXP_")) {
      return "其他费用配置";
    }
    if (startsWith(costCode, "AUX_")) {
      return "CMS辅料有效来源";
    }
    return "成本核算结果快照";
  }

  private String partSummary(CostRunPartItem part, PricePrepareItem prepareItem) {
    String source = firstText(part.getPriceSource(), prepareItem == null ? null : prepareItem.getPriceSource());
    return joinText(
        firstText(part.getPartCode(), prepareItem == null ? null : prepareItem.getMaterialCode()),
        firstText(part.getPartName(), prepareItem == null ? null : prepareItem.getMaterialName()),
        source,
        firstText(part.getRemark(), prepareItem == null ? null : prepareItem.getMessage()));
  }

  private String costSummary(CostRunCostItem cost) {
    return joinText(cost.getCostCode(), cost.getCostName(), cost.getRemark());
  }

  private Map<String, Object> pendingDetail(String message, Long pricePrepareItemId) {
    return mapOf(
        "status", STATUS_PENDING_DETAIL,
        "message", message,
        "pricePrepareItemId", pricePrepareItemId);
  }

  private <T> void eqIfText(
      LambdaQueryWrapper<T> query,
      SFunction<T, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private boolean startsWith(String value, String prefix) {
    return value != null && value.startsWith(prefix);
  }

  private LocalDate priceDate(QuoteCostRunVersion version, PricePrepareItem prepareItem) {
    String month =
        firstText(
            prepareItem == null ? null : prepareItem.getPeriodMonth(),
            version == null ? null : version.getPricingMonth());
    if (month == null || !month.matches("\\d{4}-\\d{2}")) {
      return null;
    }
    return LocalDate.parse(month + "-01");
  }

  private boolean sameAmount(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  private boolean matchesRange(PriceRangeItem row, BigDecimal quantity) {
    if (row == null || quantity == null) {
      return false;
    }
    BigDecimal low = row.getRangeLow();
    BigDecimal high = row.getRangeHigh();
    return (low == null || quantity.compareTo(low) >= 0)
        && (high == null || quantity.compareTo(high) <= 0);
  }

  private BigDecimal rangeUnitPrice(PriceRangeItem row) {
    if (row == null) {
      return null;
    }
    return row.getPriceInclTax() != null ? row.getPriceInclTax() : row.getPriceExclTax();
  }

  private String rangeText(PriceRangeItem row) {
    if (row == null) {
      return null;
    }
    String low = row.getRangeLow() == null ? "-∞" : row.getRangeLow().stripTrailingZeros().toPlainString();
    String high = row.getRangeHigh() == null ? "+∞" : row.getRangeHigh().stripTrailingZeros().toPlainString();
    return low + " - " + high;
  }

  private String json(Object value) {
    try {
      String text = objectMapper.writeValueAsString(value);
      if (text.length() <= JSON_MAX_LENGTH) {
        return text;
      }
      Map<String, Object> truncated = new LinkedHashMap<>();
      truncated.put("truncated", true);
      truncated.put("originalLength", text.length());
      truncated.put("preview", text.substring(0, JSON_MAX_LENGTH - 200));
      return objectMapper.writeValueAsString(truncated);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("成本核算底稿 JSON 序列化失败", ex);
    }
  }

  private Map<String, Object> mapOf(Object... values) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < values.length; i += 2) {
      if (values[i] != null && values[i + 1] != null) {
        map.put(String.valueOf(values[i]), values[i + 1]);
      }
    }
    return map;
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String joinText(String... values) {
    List<String> parts = new ArrayList<>();
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        parts.add(value.trim());
      }
    }
    return parts.isEmpty() ? null : String.join(" / ", parts);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String textValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return StringUtils.hasText(text) ? text.trim() : null;
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String nullSafe(String value) {
    return StringUtils.hasText(value) ? value.trim() : "UNKNOWN";
  }
}
