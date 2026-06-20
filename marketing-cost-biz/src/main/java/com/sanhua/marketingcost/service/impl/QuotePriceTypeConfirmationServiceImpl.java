package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeAdjustRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationActionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationRow;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummary;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeImportMissingRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.service.QuotePriceTypeConfirmationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuotePriceTypeConfirmationServiceImpl implements QuotePriceTypeConfirmationService {

  static final String OBJECT_NORMAL = "NORMAL";
  static final String OBJECT_MAKE_PARENT = "MAKE_PARENT";
  static final String OBJECT_MAKE_RAW = "MAKE_RAW";
  static final String OBJECT_MAKE_SCRAP = "MAKE_SCRAP";
  static final String OBJECT_MAKE_NO_SCRAP = "MAKE_NO_SCRAP";
  static final String OBJECT_PACKAGE_PARENT = "PACKAGE_PARENT";
  static final String OBJECT_PACKAGE_CHILD = "PACKAGE_CHILD";
  static final String STATUS_CONFIRMED = QuotePriceTypeConfirmItem.STATUS_CONFIRMED;
  static final String STATUS_MISSING_TYPE = QuotePriceTypeConfirmItem.STATUS_MISSING_TYPE;
  static final String STATUS_CHILD_MISSING_TYPE = QuotePriceTypeConfirmItem.STATUS_CHILD_MISSING_TYPE;

  private static final DateTimeFormatter CONFIRM_NO_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final QuoteBomConfirmationMapper bomConfirmationMapper;
  private final MaterialPriceRouterService materialPriceRouterService;
  private final MaterialPriceTypeMapper materialPriceTypeMapper;
  private final PricePrepareItemClassifier itemClassifier;
  private final PackageComponentSnapshotService packageSnapshotService;
  private final MakePartPriceGenerationService makePartPriceGenerationService;
  private final MakePartPriceCalcRowMapper makePartPriceCalcRowMapper;
  private final QuotePriceTypeConfirmBatchMapper batchMapper;
  private final QuotePriceTypeConfirmItemMapper itemMapper;

  public QuotePriceTypeConfirmationServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      QuoteBomConfirmationMapper bomConfirmationMapper,
      MaterialPriceRouterService materialPriceRouterService,
      MaterialPriceTypeMapper materialPriceTypeMapper,
      PricePrepareItemClassifier itemClassifier,
      PackageComponentSnapshotService packageSnapshotService,
      MakePartPriceGenerationService makePartPriceGenerationService,
      MakePartPriceCalcRowMapper makePartPriceCalcRowMapper,
      QuotePriceTypeConfirmBatchMapper batchMapper,
      QuotePriceTypeConfirmItemMapper itemMapper) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomConfirmationMapper = bomConfirmationMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    this.materialPriceTypeMapper = materialPriceTypeMapper;
    this.itemClassifier = itemClassifier;
    this.packageSnapshotService = packageSnapshotService;
    this.makePartPriceGenerationService = makePartPriceGenerationService;
    this.makePartPriceCalcRowMapper = makePartPriceCalcRowMapper;
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
  }

  @Override
  public QuotePriceTypeConfirmationResponse getConfirmation(
      String oaNo, Long oaFormItemId, String periodMonth) {
    Scope scope = requireScope(oaNo, oaFormItemId, periodMonth);
    List<BomCostingRow> rows = loadRows(scope);
    List<QuotePriceTypeConfirmationRow> resultRows = buildRows(scope, rows);

    QuotePriceTypeConfirmationResponse response = new QuotePriceTypeConfirmationResponse();
    response.setOaNo(scope.oaNo());
    response.setOaFormItemId(scope.oaFormItemId());
    response.setProductCode(scope.productCode());
    response.setPeriodMonth(scope.periodMonth());
    response.setBomConfirmNo(scope.bomConfirmation().getConfirmNo());
    response.setRows(resultRows);
    response.setSummary(summary(rows.size(), resultRows));
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuotePriceTypeConfirmationActionResponse importMissing(
      String oaNo, Long oaFormItemId, QuotePriceTypeImportMissingRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    Map<String, QuotePriceTypeConfirmationRow> priceableRows = priceableRowsByCode(scope);
    QuotePriceTypeConfirmationActionResponse response = new QuotePriceTypeConfirmationActionResponse();
    if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
      throw new QuoteIngestException("导入价格类型明细不能为空");
    }
    for (QuotePriceTypeImportMissingRequest.Item item : request.getItems()) {
      response.getResults().add(importOne(scope, priceableRows, item));
    }
    response.setSummary(getConfirmation(oaNo, oaFormItemId, scope.periodMonth()).getSummary());
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuotePriceTypeConfirmationActionResponse adjustPriceType(
      String oaNo, Long oaFormItemId, QuotePriceTypeAdjustRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId, null);
    QuotePriceTypeConfirmationActionResponse response = new QuotePriceTypeConfirmationActionResponse();
    QuotePriceTypeConfirmationActionResponse.RowResult result = adjustOne(scope, request);
    response.getResults().add(result);
    if ("SUCCESS".equalsIgnoreCase(result.getStatus())) {
      markActiveConfirmationsStale(scope, LocalDateTime.now());
    }
    response.setSummary(getConfirmation(oaNo, oaFormItemId, scope.periodMonth()).getSummary());
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuotePriceTypeConfirmationActionResponse confirm(
      String oaNo, Long oaFormItemId, QuotePriceTypeConfirmRequest request) {
    Scope scope = requireScope(oaNo, oaFormItemId, request == null ? null : request.getPeriodMonth());
    QuotePriceTypeConfirmationResponse current = getConfirmation(oaNo, oaFormItemId, scope.periodMonth());
    if (current.getSummary().getMissingTypeCount() != null
        && current.getSummary().getMissingTypeCount() > 0) {
      throw new QuoteIngestException("存在缺失价格类型，不能确认价格类型");
    }

    LocalDateTime now = LocalDateTime.now();
    String operator = currentUsername("system");
    invalidateOldBatches(scope, now);
    String confirmNo = generateConfirmNo(now);

    QuotePriceTypeConfirmBatch batch = new QuotePriceTypeConfirmBatch();
    batch.setConfirmNo(confirmNo);
    batch.setOaNo(scope.oaNo());
    batch.setOaFormItemId(scope.oaFormItemId());
    batch.setProductCode(scope.productCode());
    batch.setPeriodMonth(scope.periodMonth());
    batch.setBomConfirmNo(scope.bomConfirmation().getConfirmNo());
    batch.setStatus(QuotePriceTypeConfirmBatch.STATUS_CONFIRMED);
    batch.setTotalCount(current.getSummary().getReadyForPricePrepareCount());
    batch.setConfirmedCount(current.getSummary().getConfiguredTypeCount());
    batch.setGapCount(current.getSummary().getMissingTypeCount());
    batch.setReferencePriceCount(current.getSummary().getReferencePriceCount());
    batch.setConfirmedBy(operator);
    batch.setConfirmedAt(now);
    batch.setMessage(firstText(request == null ? null : request.getMessage(), "价格类型确认完成"));
    batch.setBusinessUnitType(scope.businessUnitType());
    batch.setCreatedAt(now);
    batch.setUpdatedAt(now);
    if (batchMapper.insert(batch) <= 0) {
      throw new QuoteIngestException("价格类型确认批次保存失败");
    }

    for (QuotePriceTypeConfirmationRow row : flatten(current.getRows())) {
      if (isDisplayOnlyObject(row.getObjectType())) {
        continue;
      }
      itemMapper.insert(toConfirmItem(scope, confirmNo, row, now));
    }

    QuotePriceTypeConfirmationActionResponse response = new QuotePriceTypeConfirmationActionResponse();
    response.setConfirmNo(confirmNo);
    response.setStatus(QuotePriceTypeConfirmBatch.STATUS_CONFIRMED);
    response.setSummary(current.getSummary());
    return response;
  }

  private QuotePriceTypeConfirmationActionResponse.RowResult importOne(
      Scope scope,
      Map<String, QuotePriceTypeConfirmationRow> priceableRows,
      QuotePriceTypeImportMissingRequest.Item item) {
    String materialCode = requireText(item == null ? null : item.getMaterialCode(), "materialCode");
    if (!priceableRows.containsKey(materialCode)) {
      return QuotePriceTypeConfirmationActionResponse.RowResult.of(
          materialCode, "FAILED", "料号不在当前价格类型确认取价对象中");
    }
    if (isParentObject(item.getObjectType())) {
      return QuotePriceTypeConfirmationActionResponse.RowResult.of(
          materialCode, "FAILED", "父项不允许直接维护价格类型");
    }
    PriceTypeEnum priceType = requirePriceType(item.getPriceType());
    LocalDate effectiveFrom = parseMonth(firstText(item.getEffectiveFrom(), scope.periodMonth()));
    MaterialPriceType existing = findEffectiveType(materialCode, scope.periodMonth(), effectiveFrom);
    if (existing != null) {
      return QuotePriceTypeConfirmationActionResponse.RowResult.of(
          materialCode, "FAILED", "该料号已存在有效价格类型，请刷新页面");
    }
    MaterialPriceType entity = new MaterialPriceType();
    entity.setMaterialCode(materialCode);
    entity.setMaterialName(trimToNull(item.getMaterialName()));
    entity.setPriceType(priceType.getDbText());
    entity.setPeriod(scope.periodMonth());
    entity.setPriority(1);
    entity.setEffectiveFrom(effectiveFrom);
    entity.setSource("quote_price_type_confirmation");
    entity.setSourceSystem("manual");
    entity.setBusinessUnitType(scope.businessUnitType());
    materialPriceTypeMapper.insert(entity);
    return QuotePriceTypeConfirmationActionResponse.RowResult.of(materialCode, "SUCCESS", "导入成功");
  }

  private QuotePriceTypeConfirmationActionResponse.RowResult adjustOne(
      Scope scope, QuotePriceTypeAdjustRequest request) {
    if (request == null) {
      throw new QuoteIngestException("调整价格类型请求不能为空");
    }
    String materialCode = requireText(request == null ? null : request.getMaterialCode(), "materialCode");
    if (isParentObject(request.getObjectType())) {
      throw new QuoteIngestException("父项不允许直接调整价格类型");
    }
    PriceTypeEnum newType = requirePriceType(request.getPriceType());
    LocalDate effectiveFrom = parseMonth(firstText(request.getEffectiveFrom(), scope.periodMonth()));
    MaterialPriceType existing = findEffectiveType(materialCode, scope.periodMonth(), effectiveFrom);
    if (existing == null) {
      throw new QuoteIngestException("当前有效价格类型不存在，请先走导入缺失");
    }
    Optional<PriceTypeEnum> oldType = PriceTypeEnum.fromDbText(existing.getPriceType());
    if (oldType.isPresent() && oldType.get() == newType) {
      return QuotePriceTypeConfirmationActionResponse.RowResult.of(
          materialCode, "UNCHANGED", "新旧价格类型一致，未新增版本");
    }
    if (existing.getEffectiveFrom() != null && !effectiveFrom.isAfter(existing.getEffectiveFrom())) {
      throw new QuoteIngestException("effectiveFrom 必须晚于旧记录生效开始月");
    }
    existing.setEffectiveTo(effectiveFrom.minusDays(1));
    materialPriceTypeMapper.updateById(existing);

    MaterialPriceType next = copyType(existing);
    next.setId(null);
    next.setMaterialName(firstText(request.getMaterialName(), existing.getMaterialName()));
    next.setPriceType(newType.getDbText());
    next.setPeriod(scope.periodMonth());
    next.setEffectiveFrom(effectiveFrom);
    next.setEffectiveTo(null);
    next.setSource("quote_price_type_confirmation_adjust");
    next.setSourceSystem("manual");
    materialPriceTypeMapper.insert(next);
    return QuotePriceTypeConfirmationActionResponse.RowResult.of(materialCode, "SUCCESS", "调整成功");
  }

  private void markActiveConfirmationsStale(Scope scope, LocalDateTime now) {
    batchMapper.update(
        null,
        Wrappers.<QuotePriceTypeConfirmBatch>update()
            .set("status", QuotePriceTypeConfirmBatch.STATUS_STALE)
            .set("updated_at", now)
            .eq("oa_no", scope.oaNo())
            .eq("oa_form_item_id", scope.oaFormItemId())
            .eq("product_code", scope.productCode())
            .eq("period_month", scope.periodMonth())
            .eq("status", QuotePriceTypeConfirmBatch.STATUS_CONFIRMED));
  }

  private Scope requireScope(String oaNo, Long oaFormItemId, String requestedPeriodMonth) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode = requireText(item.getMaterialNo(), "当前产品行料号");
    QuoteBomStatus bomStatus = latestBomStatus(form.getOaNo(), item.getId());
    String periodMonth =
        firstText(requestedPeriodMonth, resolvePeriodMonth(form, bomStatus));
    QuoteBomConfirmation bomConfirmation = latestActiveBomConfirmation(
        form.getOaNo(), item.getId(), productCode, periodMonth);
    if (bomConfirmation == null) {
      throw new QuoteIngestException("请先确认报价物料");
    }
    return new Scope(
        form,
        item,
        form.getOaNo(),
        item.getId(),
        productCode,
        periodMonth,
        bomConfirmation,
        firstText(item.getBusinessUnitType(), form.getBusinessUnitType()));
  }

  private OaForm requireForm(String oaNo) {
    String normalized = requireText(oaNo, "报价单号");
    OaForm form =
        oaFormMapper.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private OaFormItem requireItem(OaForm form, Long oaFormItemId) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    return item;
  }

  private QuoteBomStatus latestBomStatus(String oaNo, Long oaFormItemId) {
    return quoteBomStatusMapper.selectOne(
        Wrappers.<QuoteBomStatus>lambdaQuery()
            .eq(QuoteBomStatus::getOaNo, oaNo)
            .eq(QuoteBomStatus::getOaFormItemId, oaFormItemId)
            .orderByDesc(QuoteBomStatus::getCheckedAt)
            .orderByDesc(QuoteBomStatus::getId)
            .last("LIMIT 1"));
  }

  private QuoteBomConfirmation latestActiveBomConfirmation(
      String oaNo, Long oaFormItemId, String productCode, String periodMonth) {
    return bomConfirmationMapper.selectOne(
        Wrappers.<QuoteBomConfirmation>lambdaQuery()
            .eq(QuoteBomConfirmation::getOaNo, oaNo)
            .eq(QuoteBomConfirmation::getOaFormItemId, oaFormItemId)
            .eq(QuoteBomConfirmation::getTopProductCode, productCode)
            .eq(QuoteBomConfirmation::getPeriodMonth, periodMonth)
            .eq(QuoteBomConfirmation::getConfirmStatus, QuoteBomConfirmation.STATUS_CONFIRMED)
            .orderByDesc(QuoteBomConfirmation::getConfirmedAt)
            .orderByDesc(QuoteBomConfirmation::getId)
            .last("LIMIT 1"));
  }

  private String resolvePeriodMonth(OaForm form, QuoteBomStatus status) {
    String period =
        firstText(
            status == null ? null : status.getCostPeriodMonth(),
            trimToNull(form.getAccountingPeriodMonth()));
    if (period != null) {
      return period;
    }
    if (form.getApplyDate() != null) {
      return YearMonth.from(form.getApplyDate()).toString();
    }
    return YearMonth.now().toString();
  }

  private List<BomCostingRow> loadRows(Scope scope) {
    return bomCostingRowMapper.selectQuoteCostingSnapshot(
        scope.oaNo(), scope.oaFormItemId(), scope.productCode(), scope.periodMonth());
  }

  private List<QuotePriceTypeConfirmationRow> buildRows(Scope scope, List<BomCostingRow> rows) {
    List<PricePreparePlanItem> plans = itemClassifier.classify(rows);
    if (plans == null || plans.isEmpty()) {
      plans = defaultPlans(rows);
    }
    if (plans.stream()
        .map(PricePreparePlanItem::getItemType)
        .anyMatch(PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART::equals)) {
      makePartPriceGenerationService.generateByOa(
          scope.oaNo(), scope.businessUnitType(), scope.periodMonth());
    }
    List<QuotePriceTypeConfirmationRow> result = new ArrayList<>();
    for (PricePreparePlanItem plan : plans) {
      String type = trimToNull(plan.getItemType());
      if (PricePrepareItemClassifierImpl.ITEM_TYPE_PACKAGE_COMPONENT.equals(type)) {
        result.add(packageParentRow(scope, plan));
      } else if (PricePrepareItemClassifierImpl.ITEM_TYPE_MAKE_PART.equals(type)) {
        result.add(makeParentRow(scope, plan));
      } else {
        result.add(priceableRow(scope, plan.getBomRow(), OBJECT_NORMAL, plan.getMaterialCode(), plan.getMaterialName(), null, plan.getBomRowId()));
      }
    }
    return result;
  }

  private List<PricePreparePlanItem> defaultPlans(List<BomCostingRow> rows) {
    List<PricePreparePlanItem> plans = new ArrayList<>();
    for (BomCostingRow row : rows == null ? List.<BomCostingRow>of() : rows) {
      PricePreparePlanItem plan = new PricePreparePlanItem();
      plan.setBomRow(row);
      plan.setBomRowId(row.getId());
      plan.setMaterialCode(row.getMaterialCode());
      plan.setMaterialName(row.getMaterialName());
      plan.setItemType(PricePrepareItemClassifierImpl.ITEM_TYPE_NORMAL);
      plans.add(plan);
    }
    return plans;
  }

  private QuotePriceTypeConfirmationRow makeParentRow(Scope scope, PricePreparePlanItem plan) {
    BomCostingRow row = plan.getBomRow();
    QuotePriceTypeConfirmationRow parent = parentRow(row, OBJECT_MAKE_PARENT, plan.getMaterialCode(), plan.getMaterialName());
    List<MakePartPriceCalcRow> calcRows = latestMakePartRows(scope, plan.getMaterialCode());
    if (calcRows.isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_MISSING_TYPE);
      parent.setMessage("缺制造件价格生成结果，无法展开原材料/废料");
      return parent;
    }
    Map<String, QuotePriceTypeConfirmationRow> children = new LinkedHashMap<>();
    for (MakePartPriceCalcRow calcRow : calcRows) {
      String rawCode = trimToNull(calcRow.getChildMaterialNo());
      if (rawCode != null) {
        children.putIfAbsent(
            OBJECT_MAKE_RAW + ":" + rawCode,
            priceableRow(
                scope,
                row,
                OBJECT_MAKE_RAW,
                rawCode,
                firstText(calcRow.getChildMaterialName(), calcRow.getChildMaterialSpec()),
                plan.getMaterialCode(),
                plan.getBomRowId(),
                calcRow.getQtyPerParent()));
      }
      String scrapCode = trimToNull(calcRow.getScrapCode());
      if (Boolean.TRUE.equals(calcRow.getNoScrapConfirmed())) {
        children.putIfAbsent(
            OBJECT_MAKE_NO_SCRAP + ":" + plan.getMaterialCode(),
            noScrapRow(scope, row, calcRow, plan.getMaterialCode(), plan.getBomRowId()));
      } else if (scrapCode != null) {
        children.putIfAbsent(
            OBJECT_MAKE_SCRAP + ":" + scrapCode,
            priceableRow(
                scope,
                row,
                OBJECT_MAKE_SCRAP,
                scrapCode,
                calcRow.getScrapName(),
                plan.getMaterialCode(),
                plan.getBomRowId(),
                null));
      }
    }
    parent.getChildren().addAll(children.values());
    aggregateParent(parent);
    return parent;
  }

  private QuotePriceTypeConfirmationRow noScrapRow(
      Scope scope,
      BomCostingRow row,
      MakePartPriceCalcRow calcRow,
      String parentMaterialCode,
      Long bomRowId) {
    QuotePriceTypeConfirmationRow dto = new QuotePriceTypeConfirmationRow();
    dto.setRowKey(OBJECT_MAKE_NO_SCRAP + ":" + parentMaterialCode + ":" + (bomRowId == null ? "" : bomRowId));
    dto.setLevel(row == null ? 0 : row.getLevel());
    dto.setObjectType(OBJECT_MAKE_NO_SCRAP);
    dto.setMaterialCode(firstText(calcRow == null ? null : calcRow.getChildMaterialNo(), "-"));
    dto.setMaterialName(
        firstText(calcRow == null ? null : calcRow.getChildMaterialName(), "原材料")
            + " - 已确认无废料，废料抵扣按0处理");
    dto.setParentMaterialCode(parentMaterialCode);
    dto.setSourceBomRowId(bomRowId);
    dto.setSourceText(OBJECT_MAKE_NO_SCRAP);
    dto.setQuantity(null);
    dto.setPriceType("按0处理");
    dto.setPriceTypeSource(OBJECT_MAKE_NO_SCRAP);
    dto.setTypeStatus(STATUS_CONFIRMED);
    dto.setMessage(firstText(calcRow == null ? null : calcRow.getRemark(), "人工确认无废料，废料抵扣按0处理"));
    return dto;
  }

  private QuotePriceTypeConfirmationRow packageParentRow(Scope scope, PricePreparePlanItem plan) {
    BomCostingRow row = plan.getBomRow();
    QuotePriceTypeConfirmationRow parent =
        parentRow(row, OBJECT_PACKAGE_PARENT, plan.getMaterialCode(), plan.getMaterialName());
    PackageSnapshotRequest request = new PackageSnapshotRequest();
    request.setPackageMaterialCode(plan.getMaterialCode());
    request.setPeriodMonth(scope.periodMonth());
    request.setOaNo(scope.oaNo());
    request.setTopProductCode(scope.productCode());
    request.setBomPurpose(row == null ? null : row.getBomPurpose());
    request.setSourceType("U9");
    request.setAsOfDate(row == null ? null : row.getAsOfDate());
    PackageSnapshotResult snapshot = packageSnapshotService.ensureSnapshot(request);
    if (snapshot == null || snapshot.getDetails() == null || snapshot.getDetails().isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_MISSING_TYPE);
      parent.setMessage("包装组件结构缺失，无法展开包装子件");
      return parent;
    }
    for (PackageComponentSnapshotDetail detail : snapshot.getDetails()) {
      parent.getChildren().add(
          priceableRow(
              scope,
              row,
              OBJECT_PACKAGE_CHILD,
              detail.getChildMaterialCode(),
              detail.getChildMaterialName(),
              plan.getMaterialCode(),
              plan.getBomRowId()));
    }
    aggregateParent(parent);
    return parent;
  }

  private QuotePriceTypeConfirmationRow parentRow(
      BomCostingRow row, String objectType, String materialCode, String materialName) {
    QuotePriceTypeConfirmationRow parent = new QuotePriceTypeConfirmationRow();
    parent.setRowKey(objectType + ":" + materialCode + ":" + (row == null ? "" : row.getId()));
    parent.setLevel(row == null ? 0 : row.getLevel());
    parent.setObjectType(objectType);
    parent.setMaterialCode(materialCode);
    parent.setMaterialName(firstText(materialName, row == null ? null : row.getMaterialName()));
    parent.setParentMaterialCode(row == null ? null : row.getParentCode());
    parent.setSourceBomRowId(row == null ? null : row.getId());
    parent.setSourceText(objectType);
    parent.setQuantity(row == null ? null : row.getQtyPerParent());
    return parent;
  }

  private QuotePriceTypeConfirmationRow priceableRow(
      Scope scope,
      BomCostingRow row,
      String objectType,
      String materialCode,
      String materialName,
      String parentMaterialCode,
      Long bomRowId) {
    return priceableRow(scope, row, objectType, materialCode, materialName, parentMaterialCode, bomRowId, null);
  }

  private QuotePriceTypeConfirmationRow priceableRow(
      Scope scope,
      BomCostingRow row,
      String objectType,
      String materialCode,
      String materialName,
      String parentMaterialCode,
      Long bomRowId,
      java.math.BigDecimal quantityOverride) {
    QuotePriceTypeConfirmationRow dto = new QuotePriceTypeConfirmationRow();
    dto.setRowKey(objectType + ":" + materialCode + ":" + (bomRowId == null ? "" : bomRowId));
    dto.setLevel(row == null ? 0 : row.getLevel());
    dto.setObjectType(objectType);
    dto.setMaterialCode(materialCode);
    dto.setMaterialName(firstText(materialName, row == null ? null : row.getMaterialName()));
    dto.setParentMaterialCode(firstText(parentMaterialCode, row == null ? null : row.getParentCode()));
    dto.setSourceBomRowId(bomRowId);
    dto.setSourceText(objectType);
    dto.setQuantity(quantityOverride == null ? (row == null ? null : row.getQtyPerParent()) : quantityOverride);
    Optional<PriceTypeRoute> route =
        materialPriceRouterService.resolve(materialCode, scope.periodMonth(), firstDay(scope.periodMonth()));
    if (route.isPresent()) {
      PriceTypeRoute hit = route.get();
      dto.setPriceType(hit.priceType().getDbText());
      dto.setPriceTypeSource("MATERIAL_PRICE_TYPE");
      dto.setTypeStatus(STATUS_CONFIRMED);
      dto.setEffectiveFrom(hit.effectiveFrom());
      dto.setEffectiveTo(hit.effectiveTo());
    } else {
      dto.setTypeStatus(STATUS_MISSING_TYPE);
      dto.setMessage("缺价格类型");
    }
    return dto;
  }

  private void aggregateParent(QuotePriceTypeConfirmationRow parent) {
    if (parent.getChildren().isEmpty()) {
      parent.setTypeStatus(STATUS_CHILD_MISSING_TYPE);
      parent.setMessage("未展开到可维护取价对象");
      return;
    }
    boolean missing =
        parent.getChildren().stream()
            .anyMatch(child -> !STATUS_CONFIRMED.equals(child.getTypeStatus()));
    parent.setTypeStatus(missing ? STATUS_CHILD_MISSING_TYPE : STATUS_CONFIRMED);
    parent.setMessage(missing ? "存在子项缺价格类型" : "子项价格类型已配置");
  }

  private List<MakePartPriceCalcRow> latestMakePartRows(Scope scope, String parentMaterialNo) {
    String parentCode = trimToNull(parentMaterialNo);
    if (parentCode == null) {
      return List.of();
    }
    List<MakePartPriceCalcRow> rows =
        makePartPriceCalcRowMapper.selectList(
            Wrappers.<MakePartPriceCalcRow>lambdaQuery()
                .eq(MakePartPriceCalcRow::getOaNo, scope.oaNo())
                .eq(MakePartPriceCalcRow::getBusinessUnitType, scope.businessUnitType())
                .eq(MakePartPriceCalcRow::getPricingMonth, scope.periodMonth())
                .eq(MakePartPriceCalcRow::getParentMaterialNo, parentCode)
                .orderByDesc(MakePartPriceCalcRow::getCreatedAt)
                .orderByDesc(MakePartPriceCalcRow::getId));
    return rows == null ? List.of() : rows;
  }

  private QuotePriceTypeConfirmationSummary summary(
      int bomRowCount, List<QuotePriceTypeConfirmationRow> rows) {
    List<QuotePriceTypeConfirmationRow> flat = flatten(rows);
    QuotePriceTypeConfirmationSummary summary = new QuotePriceTypeConfirmationSummary();
    summary.setBomRowCount(bomRowCount);
    summary.setNormalCount(countObject(flat, OBJECT_NORMAL));
    summary.setMakePartCount(countObject(flat, OBJECT_MAKE_PARENT));
    summary.setPackageComponentCount(countObject(flat, OBJECT_PACKAGE_PARENT));
    int priceable = 0;
    int configured = 0;
    int missing = 0;
    int reference = 0;
    for (QuotePriceTypeConfirmationRow row : flat) {
      if (!isPriceable(row)) {
        continue;
      }
      priceable++;
      if (STATUS_CONFIRMED.equals(row.getTypeStatus())) {
        configured++;
      } else {
        missing++;
      }
      if (row.getReferenceUnitPrice() != null) {
        reference++;
      }
    }
    summary.setReadyForPricePrepareCount(priceable);
    summary.setConfiguredTypeCount(configured);
    summary.setMissingTypeCount(missing);
    summary.setReferencePriceCount(reference);
    return summary;
  }

  private int countObject(List<QuotePriceTypeConfirmationRow> flat, String objectType) {
    int count = 0;
    for (QuotePriceTypeConfirmationRow row : flat) {
      if (objectType.equals(row.getObjectType())) {
        count++;
      }
    }
    return count;
  }

  private List<QuotePriceTypeConfirmationRow> flatten(List<QuotePriceTypeConfirmationRow> rows) {
    List<QuotePriceTypeConfirmationRow> result = new ArrayList<>();
    for (QuotePriceTypeConfirmationRow row : rows == null ? List.<QuotePriceTypeConfirmationRow>of() : rows) {
      result.add(row);
      result.addAll(flatten(row.getChildren()));
    }
    return result;
  }

  private boolean isPriceable(QuotePriceTypeConfirmationRow row) {
    return row != null
        && !isParentObject(row.getObjectType())
        && !isDisplayOnlyObject(row.getObjectType());
  }

  private boolean isParentObject(String objectType) {
    return OBJECT_MAKE_PARENT.equals(objectType) || OBJECT_PACKAGE_PARENT.equals(objectType);
  }

  private boolean isDisplayOnlyObject(String objectType) {
    return OBJECT_MAKE_NO_SCRAP.equals(objectType);
  }

  private Map<String, QuotePriceTypeConfirmationRow> priceableRowsByCode(Scope scope) {
    Map<String, QuotePriceTypeConfirmationRow> result = new LinkedHashMap<>();
    for (QuotePriceTypeConfirmationRow row :
        flatten(getConfirmation(scope.oaNo(), scope.oaFormItemId(), scope.periodMonth()).getRows())) {
      if (isPriceable(row) && trimToNull(row.getMaterialCode()) != null) {
        result.put(row.getMaterialCode(), row);
      }
    }
    return result;
  }

  private MaterialPriceType findEffectiveType(
      String materialCode, String periodMonth, LocalDate effectiveDate) {
    List<MaterialPriceType> rows =
        materialPriceTypeMapper.selectList(
            Wrappers.<MaterialPriceType>lambdaQuery()
                .eq(MaterialPriceType::getMaterialCode, materialCode)
                .and(q -> q.eq(MaterialPriceType::getPeriod, periodMonth).or().isNull(MaterialPriceType::getPeriod).or().eq(MaterialPriceType::getPeriod, ""))
                .and(q -> q.isNull(MaterialPriceType::getEffectiveFrom).or().le(MaterialPriceType::getEffectiveFrom, effectiveDate))
                .and(q -> q.isNull(MaterialPriceType::getEffectiveTo).or().ge(MaterialPriceType::getEffectiveTo, effectiveDate))
                .orderByAsc(MaterialPriceType::getPriority)
                .orderByDesc(MaterialPriceType::getEffectiveFrom)
                .orderByDesc(MaterialPriceType::getId)
                .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private MaterialPriceType copyType(MaterialPriceType source) {
    MaterialPriceType target = new MaterialPriceType();
    target.setRowNo(source.getRowNo());
    target.setBillNo(source.getBillNo());
    target.setMaterialCode(source.getMaterialCode());
    target.setMaterialName(source.getMaterialName());
    target.setMaterialSpec(source.getMaterialSpec());
    target.setMaterialModel(source.getMaterialModel());
    target.setUnit(source.getUnit());
    target.setMaterialShape(source.getMaterialShape());
    target.setCategoryCode(source.getCategoryCode());
    target.setCategoryName(source.getCategoryName());
    target.setPriority(source.getPriority());
    target.setBusinessUnitType(source.getBusinessUnitType());
    return target;
  }

  private void invalidateOldBatches(Scope scope, LocalDateTime now) {
    List<QuotePriceTypeConfirmBatch> old =
        batchMapper.selectList(
            Wrappers.<QuotePriceTypeConfirmBatch>lambdaQuery()
                .eq(QuotePriceTypeConfirmBatch::getOaNo, scope.oaNo())
                .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, scope.oaFormItemId())
                .eq(QuotePriceTypeConfirmBatch::getProductCode, scope.productCode())
                .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, scope.periodMonth())
                .eq(QuotePriceTypeConfirmBatch::getStatus, QuotePriceTypeConfirmBatch.STATUS_CONFIRMED));
    for (QuotePriceTypeConfirmBatch batch : old == null ? List.<QuotePriceTypeConfirmBatch>of() : old) {
      if (scope.bomConfirmation().getConfirmNo().equals(batch.getBomConfirmNo())) {
        batch.setStatus(QuotePriceTypeConfirmBatch.STATUS_INVALID);
      } else {
        batch.setStatus(QuotePriceTypeConfirmBatch.STATUS_STALE);
      }
      batch.setUpdatedAt(now);
      batchMapper.updateById(batch);
    }
  }

  private QuotePriceTypeConfirmItem toConfirmItem(
      Scope scope, String confirmNo, QuotePriceTypeConfirmationRow row, LocalDateTime now) {
    QuotePriceTypeConfirmItem item = new QuotePriceTypeConfirmItem();
    item.setConfirmNo(confirmNo);
    item.setOaNo(scope.oaNo());
    item.setOaFormItemId(scope.oaFormItemId());
    item.setProductCode(scope.productCode());
    item.setPeriodMonth(scope.periodMonth());
    item.setBomRowId(row.getSourceBomRowId());
    item.setParentMaterialCode(row.getParentMaterialCode());
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setObjectType(row.getObjectType());
    item.setQuantity(row.getQuantity());
    item.setPriceType(row.getPriceType());
    item.setPriceTypeSource(row.getPriceTypeSource());
    item.setTypeEffectiveFrom(row.getEffectiveFrom());
    item.setTypeEffectiveTo(row.getEffectiveTo());
    item.setStatus(row.getTypeStatus());
    item.setMessage(row.getMessage());
    item.setBusinessUnitType(scope.businessUnitType());
    item.setCreatedAt(now);
    item.setUpdatedAt(now);
    return item;
  }

  private PriceTypeEnum requirePriceType(String value) {
    String normalized = trimToNull(value);
    if (normalized != null) {
      normalized =
          switch (normalized.toUpperCase()) {
            case "FIXED", "SETTLE_FIXED" -> "固定价";
            case "LINKED" -> "联动价";
            case "RANGE" -> "区间价";
            case "MAKE", "MAKE_PART" -> "自制件";
            default -> normalized;
          };
    }
    return PriceTypeEnum.fromDbText(normalized)
        .orElseThrow(() -> new QuoteIngestException("非法价格类型: " + value));
  }

  private LocalDate parseMonth(String value) {
    try {
      String text = requireText(value, "effectiveFrom");
      if (text.length() >= 7) {
        text = text.substring(0, 7);
      }
      return YearMonth.parse(text).atDay(1);
    } catch (DateTimeParseException ex) {
      throw new QuoteIngestException("effectiveFrom 格式必须为 YYYY-MM 或 YYYY-MM-DD: " + value);
    }
  }

  private LocalDate firstDay(String periodMonth) {
    return parseMonth(periodMonth);
  }

  private String generateConfirmNo(LocalDateTime now) {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    return "PT-CF-" + CONFIRM_NO_TIME_FORMAT.format(now) + "-" + suffix;
  }

  private String requireText(String value, String fieldName) {
    String text = trimToNull(value);
    if (text == null) {
      throw new QuoteIngestException(fieldName + "不能为空");
    }
    return text;
  }

  private String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String currentUsername(String fallback) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getPrincipal() == null) {
      return fallback;
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserDetails userDetails) {
      return StringUtils.hasText(userDetails.getUsername()) ? userDetails.getUsername() : fallback;
    }
    String value = principal.toString();
    return StringUtils.hasText(value) ? value : fallback;
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      QuoteBomConfirmation bomConfirmation,
      String businessUnitType) {}
}
