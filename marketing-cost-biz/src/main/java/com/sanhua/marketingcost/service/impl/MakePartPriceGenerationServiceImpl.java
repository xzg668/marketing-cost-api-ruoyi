package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.dto.MakePartProcessTypeResult;
import com.sanhua.marketingcost.dto.MakePartWeightResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import com.sanhua.marketingcost.service.MakePartWeightService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartPriceGenerationServiceImpl implements MakePartPriceGenerationService {

  private static final String STATUS_OK = MakePartPriceCalculator.STATUS_OK;
  private static final String STATUS_MISSING_BOM = "MISSING_BOM";

  private final MakePartSourceDataService sourceDataService;
  private final MakePartProcessTypePolicy processTypePolicy;
  private final MakePartWeightService weightService;
  private final MakePartScrapMappingService scrapMappingService;
  private final MaterialPriceRouterService materialPriceRouterService;
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final MakePartMaterialPriceResolveService priceResolveService;
  private final MakePartPriceCalculator calculator;
  private final MakePartPriceCalcRowMapper calcRowMapper;
  private final MakePartPriceGapItemMapper gapItemMapper;

  public MakePartPriceGenerationServiceImpl(
      MakePartSourceDataService sourceDataService,
      MakePartProcessTypePolicy processTypePolicy,
      MakePartWeightService weightService,
      MakePartScrapMappingService scrapMappingService,
      MaterialPriceRouterService materialPriceRouterService,
      LinkedPriceEnsureService linkedPriceEnsureService,
      MakePartMaterialPriceResolveService priceResolveService,
      MakePartPriceCalculator calculator,
      MakePartPriceCalcRowMapper calcRowMapper,
      MakePartPriceGapItemMapper gapItemMapper) {
    this.sourceDataService = sourceDataService;
    this.processTypePolicy = processTypePolicy;
    this.weightService = weightService;
    this.scrapMappingService = scrapMappingService;
    this.materialPriceRouterService = materialPriceRouterService;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.priceResolveService = priceResolveService;
    this.calculator = calculator;
    this.calcRowMapper = calcRowMapper;
    this.gapItemMapper = gapItemMapper;
  }

  @Override
  public MakePartPriceGenerateResponse generateByOa(
      String oaNo, String businessUnitType, String period) {
    return generate(trim(oaNo), trim(businessUnitType), trim(period), null);
  }

  @Override
  public MakePartPriceGenerateResponse generateByMaterial(
      String parentMaterialNo, String businessUnitType, String period) {
    return generate(null, trim(businessUnitType), trim(period), trim(parentMaterialNo));
  }

  @Override
  public MakePartPriceGenerateResponse generateAllLatest(String businessUnitType, String period) {
    return generate(null, trim(businessUnitType), trim(period), null);
  }

  @Override
  public String findLatestBatchId(String oaNo, String businessUnitType, String parentMaterialNo) {
    return calcRowMapper.selectLatestBatchId(trim(oaNo), trim(businessUnitType), trim(parentMaterialNo));
  }

  private MakePartPriceGenerateResponse generate(
      String oaNo, String businessUnitType, String period, String parentMaterialNo) {
    String calcBatchId = newCalcBatchId();
    String pricingPeriod = pricingPeriod(period);
    LocalDate quoteDate = quoteDate(pricingPeriod);
    List<BomCostingRow> parents =
        sourceDataService.listManufacturedParents(oaNo, businessUnitType, null).stream()
            .filter(parent -> parentMaterialNo == null
                || parentMaterialNo.equals(trim(parent.getMaterialCode())))
            .toList();
    MakePartGenerationPlan plan =
        buildGenerationPlan(parents, businessUnitType, pricingPeriod, quoteDate);
    List<MakePartPriceCalcRow> rows = new ArrayList<>();
    for (BomCostingRow parent : parents) {
      rows.addAll(buildRowsForParent(
          calcBatchId, parent, businessUnitType, pricingPeriod, quoteDate, plan));
    }
    List<MakePartPriceCalcRow> calculatedRows = calculator.calculate(rows);
    upsertRows(calculatedRows);
    upsertGapItems(buildGapItems(calculatedRows, LocalDateTime.now()));
    return summarize(calcBatchId, parents.size(), calculatedRows);
  }

  private void upsertRows(List<MakePartPriceCalcRow> rows) {
    for (MakePartPriceCalcRow row : rows) {
      // 缺数据也必须落异常行，不能静默跳过，否则页面无法定位缺 BOM、缺价或缺废料映射。
      Long existingId = findExistingRowId(row);
      if (existingId == null) {
        calcRowMapper.insert(row);
      } else {
        row.setId(existingId);
        calcRowMapper.updateById(row);
      }
    }
  }

  private Long findExistingRowId(MakePartPriceCalcRow row) {
    if (row == null || !StringUtils.hasText(row.getParentMaterialNo())) {
      return null;
    }
    // PPR-11 当前最终价口径：同一 OA + 父件 + 子件只保留一条。
    var query = Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
        .eq(MakePartPriceCalcRow::getOaNo, blankIfNull(row.getOaNo()))
        .eq(MakePartPriceCalcRow::getParentMaterialNo, trim(row.getParentMaterialNo()))
        .orderByDesc(MakePartPriceCalcRow::getId)
        .last("LIMIT 1");
    eqOrBlank(query, MakePartPriceCalcRow::getBusinessUnitType, row.getBusinessUnitType());
    eqOrBlank(query, MakePartPriceCalcRow::getChildMaterialNo, row.getChildMaterialNo());
    List<MakePartPriceCalcRow> existingRows = calcRowMapper.selectList(query);
    if (existingRows == null || existingRows.isEmpty()) {
      return null;
    }
    return existingRows.get(0).getId();
  }

  private void upsertGapItems(List<MakePartPriceGapItem> gapItems) {
    for (MakePartPriceGapItem gapItem : gapItems) {
      Long existingId = findExistingGapId(gapItem);
      if (existingId == null) {
        gapItemMapper.insert(gapItem);
      } else {
        gapItem.setId(existingId);
        gapItemMapper.updateById(gapItem);
      }
    }
  }

  private Long findExistingGapId(MakePartPriceGapItem gapItem) {
    if (gapItem == null
        || !StringUtils.hasText(gapItem.getParentMaterialNo())
        || !StringUtils.hasText(gapItem.getMissingPriceRole())
        || !StringUtils.hasText(gapItem.getMissingMaterialNo())) {
      return null;
    }
    var query = Wrappers.lambdaQuery(MakePartPriceGapItem.class)
        .eq(MakePartPriceGapItem::getOaNo, blankIfNull(gapItem.getOaNo()))
        .eq(MakePartPriceGapItem::getParentMaterialNo, trim(gapItem.getParentMaterialNo()))
        .eq(MakePartPriceGapItem::getMissingPriceRole, trim(gapItem.getMissingPriceRole()))
        .eq(MakePartPriceGapItem::getMissingMaterialNo, blankIfNull(gapItem.getMissingMaterialNo()))
        .orderByDesc(MakePartPriceGapItem::getId)
        .last("LIMIT 1");
    eqOrBlankGap(query, MakePartPriceGapItem::getChildMaterialNo, gapItem.getChildMaterialNo());
    List<MakePartPriceGapItem> existingRows = gapItemMapper.selectList(query);
    if (existingRows == null || existingRows.isEmpty()) {
      return null;
    }
    return existingRows.get(0).getId();
  }

  private void eqOrBlank(
      com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MakePartPriceCalcRow> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<MakePartPriceCalcRow, ?> column,
      String value) {
    String trimmed = trim(value);
    if (trimmed == null) {
      query.and(q -> q.isNull(column).or().eq(column, ""));
    } else {
      query.eq(column, trimmed);
    }
  }

  private void eqOrBlankGap(
      com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MakePartPriceGapItem> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<MakePartPriceGapItem, ?> column,
      String value) {
    String trimmed = trim(value);
    if (trimmed == null) {
      query.and(q -> q.isNull(column).or().eq(column, ""));
    } else {
      query.eq(column, trimmed);
    }
  }

  private List<MakePartPriceCalcRow> buildRowsForParent(
      String calcBatchId,
      BomCostingRow parent,
      String businessUnitType,
      String period,
      LocalDate quoteDate,
      MakePartGenerationPlan plan) {
    String parentCode = trim(parent.getMaterialCode());
    List<BomU9Source> children = plan.children(parentCode);
    if (children.isEmpty()) {
      MakePartPriceCalcRow missingBom = baseRow(calcBatchId, parent, businessUnitType);
      missingBom.setPricingMonth(period);
      missingBom.setStatus(STATUS_MISSING_BOM);
      missingBom.setRemark("缺 U9 直接子项(parent_material_no=" + parentCode + ")");
      return List.of(missingBom);
    }
    List<MakePartPriceCalcRow> rows = new ArrayList<>();
    for (BomU9Source child : children) {
      rows.addAll(buildRowsForChild(
          calcBatchId, parent, child, businessUnitType, period, quoteDate, plan));
    }
    return rows;
  }

  private List<MakePartPriceCalcRow> buildRowsForChild(
      String calcBatchId,
      BomCostingRow parent,
      BomU9Source child,
      String businessUnitType,
      String period,
      LocalDate quoteDate,
      MakePartGenerationPlan plan) {
    MakePartProcessTypeResult processType = processTypePolicy.resolve(child.getStockUnit());
    MakePartWeightResult weight =
        weightService.resolveWeights(parent.getMaterialCode(), child, processType.getItemProcessType());
    MakePartMaterialPriceResolveResult rawPrice =
        priceResolveService.resolveMaterialUnitPrice(
            child.getChildMaterialNo(), period, quoteDate, parent.getOaNo(), businessUnitType);
    List<MaterialScrapRef> scraps = plan.scraps(child.getChildMaterialNo());
    if (scraps.isEmpty()) {
      MakePartPriceCalcRow row =
          childBaseRow(calcBatchId, parent, child, businessUnitType, processType, weight, rawPrice, plan);
      row.setStatus(firstNonOk(processType.getStatus(), weight.getStatus()));
      if (!StringUtils.hasText(row.getStatus())) {
        row.setStatus(MakePartPriceCalculator.STATUS_MISSING_SCRAP_MAPPING);
      }
      row.setRemark(appendRemark(row.getRemark(), "缺废料映射(child_material_no="
          + child.getChildMaterialNo() + ")"));
      return List.of(row);
    }
    List<MakePartPriceCalcRow> rows = new ArrayList<>();
    for (MaterialScrapRef scrap : scraps) {
      MakePartMaterialPriceResolveResult scrapPrice =
          priceResolveService.resolveMaterialUnitPrice(
              scrap.getScrapCode(), period, quoteDate, parent.getOaNo(), businessUnitType);
      MakePartPriceCalcRow row =
          childBaseRow(calcBatchId, parent, child, businessUnitType, processType, weight, rawPrice, plan);
      row.setScrapCode(trim(scrap.getScrapCode()));
      row.setScrapName(trim(scrap.getScrapName()));
      row.setScrapPriceType(scrapPrice == null ? null : scrapPrice.getPriceType());
      row.setScrapUnitPrice(scrapPrice == null ? null : scrapPrice.getUnitPrice());
      row.setStatus(firstNonOk(processType.getStatus(), weight.getStatus()));
      row.setRemark(appendRemark(row.getRemark(), priceRemark("废料", scrapPrice)));
      row.setRemark(appendRemark(row.getRemark(), linkedEnsureRemark("废料", scrap.getScrapCode(), plan)));
      rows.add(row);
    }
    return rows;
  }

  private MakePartPriceCalcRow childBaseRow(
      String calcBatchId,
      BomCostingRow parent,
      BomU9Source child,
      String businessUnitType,
      MakePartProcessTypeResult processType,
      MakePartWeightResult weight,
      MakePartMaterialPriceResolveResult rawPrice,
      MakePartGenerationPlan plan) {
    MakePartPriceCalcRow row = baseRow(calcBatchId, parent, businessUnitType);
    row.setPricingMonth(plan.period);
    row.setItemProcessType(processType.getItemProcessType());
    row.setChildMaterialNo(trim(child.getChildMaterialNo()));
    row.setChildMaterialName(trim(child.getChildMaterialName()));
    row.setChildMaterialSpec(trim(child.getChildMaterialSpec()));
    row.setStockUnit(trim(child.getStockUnit()));
    row.setQtyPerParent(child.getQtyPerParent());
    row.setGrossWeightG(weight.getGrossWeightG());
    row.setNetWeightG(weight.getNetWeightG());
    row.setRawPriceType(rawPrice == null ? null : rawPrice.getPriceType());
    row.setRawUnitPrice(rawPrice == null ? null : rawPrice.getUnitPrice());
    row.setOutsourceFee(BigDecimal.ZERO);
    row.setRemark(appendRemark(processType.getRemark(), weight.getRemark()));
    row.setRemark(appendRemark(row.getRemark(), priceRemark("原材料", rawPrice)));
    row.setRemark(appendRemark(
        row.getRemark(), linkedEnsureRemark("原材料", child.getChildMaterialNo(), plan)));
    return row;
  }

  private List<MakePartPriceGapItem> buildGapItems(
      List<MakePartPriceCalcRow> rows, LocalDateTime generatedAt) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<MakePartPriceGapItem> gapItems = new ArrayList<>();
    for (MakePartPriceCalcRow row : rows) {
      if (row == null || !StringUtils.hasText(row.getChildMaterialNo())) {
        continue;
      }
      // 缺价清单只沉淀后续 OA 补价输入：原材料价和废料价分开拆行，不在这里调用 OA。
      if (row.getRawUnitPrice() == null) {
        gapItems.add(gapItem(row, "RAW", row.getChildMaterialNo(), row.getChildMaterialName(),
            row.getRawPriceType(), "原材料价缺失(child_material_no=" + row.getChildMaterialNo() + ")",
            generatedAt));
      }
      if (StringUtils.hasText(row.getScrapCode()) && row.getScrapUnitPrice() == null) {
        gapItems.add(gapItem(row, "SCRAP", row.getScrapCode(), row.getScrapName(),
            row.getScrapPriceType(), "废料价缺失(scrap_code=" + row.getScrapCode() + ")",
            generatedAt));
      }
    }
    return gapItems;
  }

  private MakePartPriceGapItem gapItem(
      MakePartPriceCalcRow row,
      String missingPriceRole,
      String missingMaterialNo,
      String missingMaterialName,
      String priceType,
      String reason,
      LocalDateTime generatedAt) {
    MakePartPriceGapItem item = new MakePartPriceGapItem();
    item.setCalcBatchId(row.getCalcBatchId());
    item.setPricingMonth(row.getPricingMonth());
    item.setGeneratedAt(generatedAt);
    item.setOaNo(row.getOaNo());
    item.setBusinessUnitType(row.getBusinessUnitType());
    item.setParentMaterialNo(row.getParentMaterialNo());
    item.setParentMaterialName(row.getParentMaterialName());
    item.setChildMaterialNo(blankIfNull(row.getChildMaterialNo()));
    item.setChildMaterialName(row.getChildMaterialName());
    item.setChildMaterialSpec(row.getChildMaterialSpec());
    item.setScrapCode(blankIfNull(row.getScrapCode()));
    item.setScrapName(row.getScrapName());
    item.setMissingPriceRole(missingPriceRole);
    item.setMissingMaterialNo(blankIfNull(missingMaterialNo));
    item.setMissingMaterialName(trim(missingMaterialName));
    item.setPriceType(trim(priceType));
    item.setReason(reason);
    item.setOaPushStatus("NOT_PUSHED");
    return item;
  }

  private MakePartPriceCalcRow baseRow(
      String calcBatchId, BomCostingRow parent, String businessUnitType) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setCalcBatchId(calcBatchId);
    row.setOaNo(blankIfNull(parent.getOaNo()));
    row.setBusinessUnitType(businessUnitType);
    row.setParentMaterialNo(trim(parent.getMaterialCode()));
    row.setParentMaterialName(trim(parent.getMaterialName()));
    row.setDrawingNo(trim(parent.getMaterialSpec()));
    row.setChildMaterialNo("");
    row.setOutsourceFee(BigDecimal.ZERO);
    return row;
  }

  private MakePartGenerationPlan buildGenerationPlan(
      List<BomCostingRow> parents,
      String businessUnitType,
      String period,
      LocalDate quoteDate) {
    MakePartGenerationPlan plan = new MakePartGenerationPlan();
    plan.period = period;
    if (parents == null || parents.isEmpty()) {
      return plan;
    }
    for (BomCostingRow parent : parents) {
      String parentCode = trim(parent.getMaterialCode());
      List<BomU9Source> children = sourceDataService.listDedupedChildren(parentCode);
      plan.childrenByParent.put(parentCode, children == null ? List.of() : children);
      for (BomU9Source child : plan.children(parentCode)) {
        String childCode = trim(child.getChildMaterialNo());
        collectLinkedEnsureCode(plan, trim(parent.getOaNo()), businessUnitType, period, quoteDate,
            childCode);
        List<MaterialScrapRef> scraps = scrapMappingService.listMappings(childCode, businessUnitType);
        plan.scrapsByChild.put(childCode, scraps == null ? List.of() : scraps);
        for (MaterialScrapRef scrap : plan.scraps(childCode)) {
          collectLinkedEnsureCode(plan, trim(parent.getOaNo()), businessUnitType, period, quoteDate,
              trim(scrap.getScrapCode()));
        }
      }
    }
    ensureLinkedPrices(plan, businessUnitType, period);
    return plan;
  }

  private void collectLinkedEnsureCode(
      MakePartGenerationPlan plan,
      String oaNo,
      String businessUnitType,
      String period,
      LocalDate quoteDate,
      String materialCode) {
    String code = trim(materialCode);
    if (code == null || !hasLinkedRoute(code, period, quoteDate)) {
      return;
    }
    if (!StringUtils.hasText(oaNo)) {
      plan.ensureFailures.put(code, "联动价 ensure 失败：QUOTE 场景缺 OA 单号(material_code=" + code + ")");
      return;
    }
    if (!StringUtils.hasText(businessUnitType) || !StringUtils.hasText(period)) {
      plan.ensureFailures.put(code, "联动价 ensure 失败：缺业务单元或期间(material_code=" + code + ")");
      return;
    }
    plan.linkedCodesByOa.computeIfAbsent(oaNo, ignored -> new LinkedHashSet<>()).add(code);
  }

  private boolean hasLinkedRoute(String materialCode, String period, LocalDate quoteDate) {
    List<PriceTypeRoute> routes =
        materialPriceRouterService.listCandidates(materialCode, period, quoteDate);
    if (routes == null || routes.isEmpty()) {
      return false;
    }
    for (PriceTypeRoute route : routes) {
      if (route != null && route.priceType() == PriceTypeEnum.LINKED) {
        return true;
      }
    }
    return false;
  }

  private void ensureLinkedPrices(
      MakePartGenerationPlan plan, String businessUnitType, String period) {
    for (Map.Entry<String, Set<String>> entry : plan.linkedCodesByOa.entrySet()) {
      String oaNo = entry.getKey();
      Set<String> itemCodes = entry.getValue();
      if (itemCodes == null || itemCodes.isEmpty()) {
        continue;
      }
      try {
        // 自制件生成是业务入口前置 ensure：只确保本次会取价的联动价料号，不做全量预生成。
        LinkedPriceEnsureResult result = linkedPriceEnsureService.ensure(
            LinkedPriceEnsureRequest.quote(oaNo, businessUnitType, period, itemCodes));
        if (result != null && result.getFailedItems() != null) {
          for (LinkedPriceEnsureResult.FailedItem failed : result.getFailedItems()) {
            if (failed != null && StringUtils.hasText(failed.getItemCode())) {
              plan.ensureFailures.put(
                  trim(failed.getItemCode()),
                  "联动价 ensure 失败(material_code=" + trim(failed.getItemCode())
                      + "): " + failed.getReason());
            }
          }
        }
      } catch (RuntimeException ex) {
        for (String code : itemCodes) {
          plan.ensureFailures.put(
              code,
              "联动价 ensure 异常(material_code=" + code + "): " + ex.getMessage());
        }
      }
    }
  }

  private String linkedEnsureRemark(
      String label, String materialCode, MakePartGenerationPlan plan) {
    if (plan == null) {
      return null;
    }
    String code = trim(materialCode);
    if (code == null) {
      return null;
    }
    String failure = plan.ensureFailures.get(code);
    return StringUtils.hasText(failure) ? label + failure : null;
  }

  private MakePartPriceGenerateResponse summarize(
      String calcBatchId, int parentCount, List<MakePartPriceCalcRow> rows) {
    Map<String, Integer> statusSummary = new LinkedHashMap<>();
    for (MakePartPriceCalcRow row : rows) {
      String status = StringUtils.hasText(row.getStatus()) ? row.getStatus() : "UNKNOWN";
      statusSummary.merge(status, 1, Integer::sum);
    }
    int okCount = statusSummary.getOrDefault(STATUS_OK, 0);
    int errorCount = rows.size() - okCount;
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse(calcBatchId, parentCount, rows.size(), okCount, 0, errorCount);
    response.setTotalCount(rows.size());
    response.setStatusSummary(statusSummary);
    return response;
  }

  private String firstNonOk(String... statuses) {
    for (String status : statuses) {
      if (StringUtils.hasText(status) && !STATUS_OK.equals(status)) {
        return status;
      }
    }
    return null;
  }

  private String priceRemark(String label, MakePartMaterialPriceResolveResult price) {
    if (price == null) {
      return label + "价格结果为空";
    }
    if (STATUS_OK.equals(price.getStatus())) {
      return price.getRemark();
    }
    return label + "取价异常(status=" + price.getStatus() + ", material_code="
        + price.getMaterialCode() + "): " + price.getRemark();
  }

  private LocalDate quoteDate(String period) {
    if (StringUtils.hasText(period)) {
      try {
        return YearMonth.parse(period.trim()).atEndOfMonth();
      } catch (DateTimeParseException ignored) {
        return LocalDate.now();
      }
    }
    return LocalDate.now();
  }

  private String pricingPeriod(String period) {
    return StringUtils.hasText(period) ? period.trim() : YearMonth.now().toString();
  }

  private String newCalcBatchId() {
    // 每次生成保留独立批次，便于追溯当时公式、价格来源和异常数据。
    return "MPPG-" + UUID.randomUUID();
  }

  private String appendRemark(String first, String second) {
    if (!StringUtils.hasText(first)) {
      return StringUtils.hasText(second) ? second : null;
    }
    if (!StringUtils.hasText(second)) {
      return first;
    }
    return first + "；" + second;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String blankIfNull(String value) {
    String trimmed = trim(value);
    return trimmed == null ? "" : trimmed;
  }

  private static class MakePartGenerationPlan {
    private String period;
    private final Map<String, List<BomU9Source>> childrenByParent = new LinkedHashMap<>();
    private final Map<String, List<MaterialScrapRef>> scrapsByChild = new LinkedHashMap<>();
    private final Map<String, Set<String>> linkedCodesByOa = new LinkedHashMap<>();
    private final Map<String, String> ensureFailures = new LinkedHashMap<>();

    private List<BomU9Source> children(String parentCode) {
      return childrenByParent.getOrDefault(parentCode, List.of());
    }

    private List<MaterialScrapRef> scraps(String childCode) {
      return scrapsByChild.getOrDefault(childCode, List.of());
    }
  }
}
