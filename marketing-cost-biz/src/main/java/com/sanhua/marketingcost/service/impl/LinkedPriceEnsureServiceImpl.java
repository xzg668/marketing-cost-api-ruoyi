package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.LinkedPriceFactorSource;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelection;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidenceFactory;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LinkedPriceEnsureServiceImpl implements LinkedPriceEnsureService {
  private static final String CALC_STATUS_OK = "OK";
  private static final String CALC_STATUS_FAILED = "FAILED";

  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final OaFormMapper oaFormMapper;
  private final PriceLinkedCalcServiceImpl priceLinkedCalcService;
  private final SupplierPreferredPriceSelector supplierPreferredPriceSelector;

  public LinkedPriceEnsureServiceImpl(
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      BomCostingRowMapper bomCostingRowMapper,
      OaFormMapper oaFormMapper,
      PriceLinkedCalcServiceImpl priceLinkedCalcService,
      SupplierPreferredPriceSelector supplierPreferredPriceSelector) {
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.oaFormMapper = oaFormMapper;
    this.priceLinkedCalcService = priceLinkedCalcService;
    this.supplierPreferredPriceSelector = supplierPreferredPriceSelector;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PriceLinkedCalcItem> calculate(LinkedPriceEnsureRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("联动价 calculate 请求不能为空");
    }
    Set<String> itemCodes = new TreeSet<>(request.normalizedItemCodes());
    if (itemCodes.isEmpty()) {
      return List.of();
    }
    List<String> errors = request.validate();
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("；", errors));
    }
    if (request.getCalcScene() != LinkedPriceCalcScene.QUOTE) {
      throw new IllegalArgumentException("只读价格检查当前仅支持报价联动价场景");
    }

    String oaNo = request.getOaNo().trim();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    LocalDateTime priceAsOfTime = resolvedPriceAsOfTime(request);
    String factorSource = quoteFactorSource(request);
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate());
    Map<String, BomSnapshot> bomMap =
        fetchBomSnapshots(oaNo, businessUnitType, itemCodes);
    OaForm oaForm = fetchOaForm(oaNo);
    List<PriceLinkedCalcItem> calculated = new ArrayList<>(itemCodes.size());
    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
      populateQuoteContext(
          calcItem,
          oaNo,
          businessUnitType,
          pricingMonth,
          priceAsOfTime,
          factorSource,
          itemCode,
          bomMap);
      calculateQuoteItem(calcItem, linkedItemMap.get(itemCode), oaForm, request, factorSource);
      calculated.add(calcItem);
    }
    return calculated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public LinkedPriceEnsureResult ensure(LinkedPriceEnsureRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("联动价 ensure 请求不能为空");
    }
    // 多产品并发可能包含相同联动料号；统一锁定顺序，避免事务按不同料号顺序互相等待。
    Set<String> itemCodes = new TreeSet<>(request.normalizedItemCodes());
    LinkedPriceEnsureResult result = new LinkedPriceEnsureResult();
    result.setRequestedCount(itemCodes.size());
    if (itemCodes.isEmpty()) {
      return result;
    }

    List<String> errors = request.validate();
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("；", errors));
    }
    if (request.getCalcScene() == LinkedPriceCalcScene.MONTHLY_ADJUST) {
      return ensureMonthlyAdjust(request, itemCodes, result);
    }
    if (request.getCalcScene() != LinkedPriceCalcScene.QUOTE) {
      throw new IllegalArgumentException("不支持的联动价计算场景：" + request.getCalcScene());
    }
    return ensureQuote(request, itemCodes, result);
  }

  private LinkedPriceEnsureResult ensureQuote(
      LinkedPriceEnsureRequest request,
      Set<String> itemCodes,
      LinkedPriceEnsureResult result) {
    String oaNo = request.getOaNo().trim();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    LocalDateTime priceAsOfTime = resolvedPriceAsOfTime(request);
    String factorSource = quoteFactorSource(request);
    Map<String, PriceLinkedCalcItem> existingMap =
        fetchExistingQuoteResults(
            oaNo,
            businessUnitType,
            pricingMonth,
            priceAsOfTime,
            factorSource,
            itemCodes);
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate());
    Map<String, BomSnapshot> bomMap = fetchBomSnapshots(oaNo, businessUnitType, itemCodes);
    OaForm oaForm = fetchOaForm(oaNo);

    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem existing = existingMap.get(itemCode);
      LinkedPriceCandidate candidate = linkedItemMap.get(itemCode);
      if (canSkip(existing, candidate, request.isForceRefresh())) {
        result.setSkippedCount(result.getSkippedCount() + 1);
        continue;
      }
      boolean created = existing == null;
      PriceLinkedCalcItem calcItem = created
          ? new PriceLinkedCalcItem()
          : existing;
      populateQuoteContext(
          calcItem,
          oaNo,
          businessUnitType,
          pricingMonth,
          priceAsOfTime,
          factorSource,
          itemCode,
          bomMap);
      calculateQuoteItem(calcItem, candidate, oaForm, request, factorSource);
      persist(calcItem, created);
      if (created) {
        result.setCreatedCount(result.getCreatedCount() + 1);
      } else {
        result.setUpdatedCount(result.getUpdatedCount() + 1);
      }
      if (CALC_STATUS_FAILED.equalsIgnoreCase(calcItem.getCalcStatus())) {
        result.addFailedItem(itemCode, calcItem.getFailureCode(), calcItem.getCalcMessage());
      }
    }
    return result;
  }

  private LinkedPriceEnsureResult ensureMonthlyAdjust(
      LinkedPriceEnsureRequest request,
      Set<String> itemCodes,
      LinkedPriceEnsureResult result) {
    Long adjustBatchId = request.getAdjustBatchId();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    LocalDateTime priceAsOfTime = resolvedPriceAsOfTime(request);
    Map<String, PriceLinkedCalcItem> existingMap =
        fetchExistingMonthlyAdjustResults(adjustBatchId, businessUnitType, pricingMonth, itemCodes);
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate());

    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem existing = existingMap.get(itemCode);
      LinkedPriceCandidate candidate = linkedItemMap.get(itemCode);
      if (canSkip(existing, candidate, request.isForceRefresh())) {
        result.setSkippedCount(result.getSkippedCount() + 1);
        continue;
      }
      boolean created = existing == null;
      PriceLinkedCalcItem calcItem = created
          ? new PriceLinkedCalcItem()
          : existing;
      populateMonthlyAdjustContext(
          calcItem, adjustBatchId, businessUnitType, pricingMonth, priceAsOfTime, itemCode);
      calculateMonthlyAdjustItem(calcItem, candidate);
      persist(calcItem, created);
      if (created) {
        result.setCreatedCount(result.getCreatedCount() + 1);
      } else {
        result.setUpdatedCount(result.getUpdatedCount() + 1);
      }
      if (CALC_STATUS_FAILED.equalsIgnoreCase(calcItem.getCalcStatus())) {
        result.addFailedItem(itemCode, calcItem.getFailureCode(), calcItem.getCalcMessage());
      }
    }
    return result;
  }

  private Map<String, PriceLinkedCalcItem> fetchExistingQuoteResults(
      String oaNo,
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String factorSource,
      Set<String> itemCodes) {
    var query = Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
        .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.QUOTE.getCode())
        .eq(PriceLinkedCalcItem::getOaNo, oaNo)
        .eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType)
        .eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth)
        .eq(PriceLinkedCalcItem::getFactorSource, factorSource)
        .in(PriceLinkedCalcItem::getItemCode, itemCodes);
    if (priceAsOfTime != null) {
      query.eq(PriceLinkedCalcItem::getPriceAsOfTime, priceAsOfTime);
    }
    List<PriceLinkedCalcItem> rows =
        priceLinkedCalcItemMapper.selectList(query.orderByDesc(PriceLinkedCalcItem::getId));
    Map<String, PriceLinkedCalcItem> map = new LinkedHashMap<>();
    for (PriceLinkedCalcItem row : rows) {
      if (StringUtils.hasText(row.getItemCode())) {
        map.putIfAbsent(row.getItemCode().trim(), row);
      }
    }
    return map;
  }

  private Map<String, PriceLinkedCalcItem> fetchExistingMonthlyAdjustResults(
      Long adjustBatchId, String businessUnitType, String pricingMonth, Set<String> itemCodes) {
    var query = Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
        .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.MONTHLY_ADJUST.getCode())
        .eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType)
        .eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth)
        .in(PriceLinkedCalcItem::getItemCode, itemCodes);
    if (adjustBatchId == null) {
      query.isNull(PriceLinkedCalcItem::getAdjustBatchId);
    } else {
      query.eq(PriceLinkedCalcItem::getAdjustBatchId, adjustBatchId);
    }
    List<PriceLinkedCalcItem> rows = priceLinkedCalcItemMapper.selectList(query);
    Map<String, PriceLinkedCalcItem> map = new LinkedHashMap<>();
    for (PriceLinkedCalcItem row : rows) {
      if (StringUtils.hasText(row.getItemCode())) {
        map.put(row.getItemCode().trim(), row);
      }
    }
    return map;
  }

  private Map<String, LinkedPriceCandidate> fetchLinkedItems(
      String businessUnitType, String pricingMonth, Set<String> itemCodes, LocalDate priceDate) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getDeleted, 0)
        .eq(PriceLinkedItem::getBusinessUnitType, businessUnitType)
        .le(PriceLinkedItem::getPricingMonth, pricingMonth)
        .in(PriceLinkedItem::getMaterialCode, itemCodes);
    List<PriceLinkedItem> rows = priceLinkedItemMapper.selectList(
        query.orderByDesc(PriceLinkedItem::getPricingMonth)
            .orderByDesc(PriceLinkedItem::getCreatedAt)
            .orderByDesc(PriceLinkedItem::getId));
    Map<String, List<PriceLinkedItem>> candidatesByMaterial = new LinkedHashMap<>();
    for (PriceLinkedItem row : rows) {
      if (StringUtils.hasText(row.getMaterialCode())) {
        candidatesByMaterial.computeIfAbsent(row.getMaterialCode().trim(), key -> new ArrayList<>())
            .add(row);
      }
    }
    Map<String, LinkedPriceCandidate> map = new LinkedHashMap<>();
    for (Map.Entry<String, List<PriceLinkedItem>> entry : candidatesByMaterial.entrySet()) {
      List<PriceLinkedItem> candidates = currentSupplierFormulas(entry.getValue());
      PriceLinkedItem fallback = candidates.isEmpty() ? null : candidates.get(0);
      SupplierPreferredPriceSelection<PriceLinkedItem> selection =
          supplierPreferredPriceSelector.select(
              candidates,
              businessUnitType,
              entry.getKey(),
              fallback == null ? null : fallback.getMaterialName(),
              fallback == null ? null : fallback.getSpecModel(),
              priceDate,
              PriceLinkedItem::getSupplierName,
              PriceLinkedItem::getSupplierCode);
      map.put(entry.getKey(), new LinkedPriceCandidate(selection.row(), selection));
    }
    return map;
  }

  /**
   * 联动公式正式版本选择：先取不超过核算月的最大价格月份，再按供应商保留最新成功导入行。
   *
   * <p>{@code effective_from/effective_to} 是历史公式生命周期元数据，不是 Excel 价格数据，
   * 不参与报价公式选择。同月同供应商的先后顺序只认正式入库时间 {@code created_at}，
   * 时间相同时用自增主键 {@code id} 稳定兜底。供应商代码优先；代码为空时才按标准化名称分组。
   */
  private List<PriceLinkedItem> currentSupplierFormulas(List<PriceLinkedItem> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }
    String latestPricingMonth = candidates.stream()
        .map(PriceLinkedItem::getPricingMonth)
        .filter(StringUtils::hasText)
        .max(String::compareTo)
        .orElse(null);
    if (!StringUtils.hasText(latestPricingMonth)) {
      return List.of();
    }
    Map<String, PriceLinkedItem> latestBySupplier = new LinkedHashMap<>();
    for (PriceLinkedItem candidate : candidates) {
      if (candidate == null || !latestPricingMonth.equals(candidate.getPricingMonth())) {
        continue;
      }
      String supplierKey = linkedSupplierKey(candidate);
      PriceLinkedItem current = latestBySupplier.get(supplierKey);
      if (current == null || importedAfter(candidate, current)) {
        latestBySupplier.put(supplierKey, candidate);
      }
    }
    return latestBySupplier.values().stream()
        .sorted((left, right) -> compareImportVersion(right, left))
        .toList();
  }

  private String linkedSupplierKey(PriceLinkedItem item) {
    String supplierCode = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
        item == null ? null : item.getSupplierCode());
    if (StringUtils.hasText(supplierCode)) {
      return "CODE:" + supplierCode;
    }
    String supplierName = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
        item == null ? null : item.getSupplierName());
    return StringUtils.hasText(supplierName) ? "NAME:" + supplierName : "NO_SUPPLIER";
  }

  private boolean importedAfter(PriceLinkedItem candidate, PriceLinkedItem current) {
    return compareImportVersion(candidate, current) > 0;
  }

  private int compareImportVersion(PriceLinkedItem left, PriceLinkedItem right) {
    LocalDateTime leftCreatedAt = left == null ? null : left.getCreatedAt();
    LocalDateTime rightCreatedAt = right == null ? null : right.getCreatedAt();
    int createdAtCompare = java.util.Comparator.nullsFirst(LocalDateTime::compareTo)
        .compare(leftCreatedAt, rightCreatedAt);
    if (createdAtCompare != 0) {
      return createdAtCompare;
    }
    Long leftId = left == null ? null : left.getId();
    Long rightId = right == null ? null : right.getId();
    return java.util.Comparator.nullsFirst(Long::compareTo).compare(leftId, rightId);
  }

  private Map<String, BomSnapshot> fetchBomSnapshots(
      String oaNo, String businessUnitType, Set<String> itemCodes) {
    List<BomCostingRow> rows = bomCostingRowMapper.selectList(
        Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getOaNo, oaNo)
            .eq(BomCostingRow::getBusinessUnitType, businessUnitType)
            .in(BomCostingRow::getMaterialCode, itemCodes));
    Map<String, BomSnapshot> map = new HashMap<>();
    for (BomCostingRow row : rows) {
      if (!StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      String itemCode = row.getMaterialCode().trim();
      BomSnapshot snapshot = map.computeIfAbsent(itemCode, key -> new BomSnapshot());
      if (row.getQtyPerTop() != null) {
        snapshot.bomQty = snapshot.bomQty == null
            ? row.getQtyPerTop()
            : snapshot.bomQty.add(row.getQtyPerTop());
      }
      if (!StringUtils.hasText(snapshot.shapeAttr) && StringUtils.hasText(row.getShapeAttr())) {
        snapshot.shapeAttr = row.getShapeAttr().trim();
      }
    }
    return map;
  }

  private OaForm fetchOaForm(String oaNo) {
    return oaFormMapper.selectOne(
        Wrappers.lambdaQuery(OaForm.class)
            .eq(OaForm::getOaNo, oaNo)
            .last("LIMIT 1"));
  }

  private boolean canSkip(
      PriceLinkedCalcItem existing,
      LinkedPriceCandidate candidate,
      boolean forceRefresh) {
    return existing != null
        && !forceRefresh
        && candidate != null
        && candidate.row() != null
        && !candidate.selection().failed()
        && java.util.Objects.equals(existing.getSourcePriceRecordId(), candidate.row().getId())
        && CALC_STATUS_OK.equalsIgnoreCase(existing.getCalcStatus())
        && existing.getPartUnitPrice() != null;
  }

  private void calculateQuoteItem(
      PriceLinkedCalcItem calcItem,
      LinkedPriceCandidate candidate,
      OaForm oaForm,
      LinkedPriceEnsureRequest request,
      String factorSource) {
    if (applySelectionFailure(calcItem, candidate)) {
      return;
    }
    PriceLinkedItem linkedItem = candidate == null ? null : candidate.row();
    try {
      if (request.getPriceScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE) {
        priceLinkedCalcService.calculateQuoteItemForEnsure(
            calcItem,
            linkedItem,
            oaForm,
            request.normalizedVariableOverrides(),
            factorSource);
      } else {
        priceLinkedCalcService.calculateQuoteItemForEnsure(calcItem, linkedItem, oaForm);
      }
      applySelectionEvidence(calcItem, candidate);
      applyFormulaCarryForwardWarning(calcItem);
    } catch (RuntimeException ex) {
      markFailed(calcItem, null, ex.getMessage());
    }
  }

  private void calculateMonthlyAdjustItem(
      PriceLinkedCalcItem calcItem, LinkedPriceCandidate candidate) {
    if (applySelectionFailure(calcItem, candidate)) {
      return;
    }
    try {
      priceLinkedCalcService.calculateMonthlyAdjustItemForEnsure(
          calcItem, candidate == null ? null : candidate.row());
      applySelectionEvidence(calcItem, candidate);
      applyFormulaCarryForwardWarning(calcItem);
    } catch (RuntimeException ex) {
      markFailed(calcItem, null, ex.getMessage());
    }
  }

  private boolean applySelectionFailure(
      PriceLinkedCalcItem calcItem, LinkedPriceCandidate candidate) {
    if (candidate == null || !candidate.selection().failed()) {
      return false;
    }
    markFailed(
        calcItem,
        candidate.selection().failureCode(),
        candidate.selection().failureMessage());
    return true;
  }

  private void markFailed(PriceLinkedCalcItem calcItem, String failureCode, String message) {
    clearSelectionEvidence(calcItem);
    calcItem.setPartUnitPrice(null);
    calcItem.setPartAmount(null);
    calcItem.setCalcStatus(CALC_STATUS_FAILED);
    calcItem.setFailureCode(failureCode);
    calcItem.setCalcMessage(message);
  }

  private void applySelectionEvidence(
      PriceLinkedCalcItem calcItem, LinkedPriceCandidate candidate) {
    if (candidate == null || candidate.row() == null) {
      clearSelectionEvidence(calcItem);
      return;
    }
    PriceLinkedItem row = candidate.row();
    SupplierPreferredPriceSelection<PriceLinkedItem> selection = candidate.selection();
    LocalDate pricingDate = calcItem.getPriceAsOfTime() == null
        ? null
        : calcItem.getPriceAsOfTime().toLocalDate();
    PriceResolveEvidence evidence = PriceResolveEvidenceFactory.create(
        row.getId(),
        row.getSourceUploadBatchId() == null ? null : row.getSourceUploadBatchId().toString(),
        firstText(selection.mainSupplierName(), row.getSupplierName()),
        firstText(selection.mainSupplierCode(), row.getSupplierCode()),
        selection.supplyRatio(),
        selection.supplyRatioRecordId(),
        null,
        null,
        pricingDate);
    calcItem.setSourcePriceRecordId(evidence.sourcePriceRecordId());
    calcItem.setSourcePriceBatchNo(evidence.sourceBatchNo());
    calcItem.setSupplierName(evidence.supplierName());
    calcItem.setSupplierCode(evidence.supplierCode());
    calcItem.setSupplyRatio(evidence.supplyRatio());
    calcItem.setSupplyRatioRecordId(evidence.supplyRatioRecordId());
    calcItem.setSourceEffectiveFrom(evidence.effectiveFrom());
    calcItem.setSourceEffectiveTo(evidence.effectiveTo());
    calcItem.setCarriedForward(evidence.carriedForward() ? 1 : 0);
    calcItem.setWarningMessage(evidence.warningMessage());
    calcItem.setFailureCode(null);
  }

  private void clearSelectionEvidence(PriceLinkedCalcItem calcItem) {
    calcItem.setSourcePriceRecordId(null);
    calcItem.setSourcePriceBatchNo(null);
    calcItem.setSupplierName(null);
    calcItem.setSupplierCode(null);
    calcItem.setSupplyRatio(null);
    calcItem.setSupplyRatioRecordId(null);
    calcItem.setSourceEffectiveFrom(null);
    calcItem.setSourceEffectiveTo(null);
    calcItem.setCarriedForward(0);
    calcItem.setWarningMessage(null);
    calcItem.setFailureCode(null);
  }

  private void applyFormulaCarryForwardWarning(PriceLinkedCalcItem calcItem) {
    if (calcItem == null
        || !StringUtils.hasText(calcItem.getTraceJson())
        || !calcItem.getTraceJson().contains("_CARRIED_FORWARD")) {
      return;
    }
    calcItem.setCarriedForward(1);
    String formulaWarning = "联动价公式沿用历史月份的正式影响因素价，请财务关注";
    if (StringUtils.hasText(calcItem.getWarningMessage())) {
      if (!calcItem.getWarningMessage().contains(formulaWarning)) {
        calcItem.setWarningMessage(calcItem.getWarningMessage().trim() + "；" + formulaWarning);
      }
    } else {
      calcItem.setWarningMessage(formulaWarning);
    }
  }

  private String firstText(String preferred, String fallback) {
    return StringUtils.hasText(preferred)
        ? preferred.trim()
        : StringUtils.hasText(fallback) ? fallback.trim() : null;
  }

  private void populateQuoteContext(
      PriceLinkedCalcItem calcItem,
      String oaNo,
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String factorSource,
      String itemCode,
      Map<String, BomSnapshot> bomMap) {
    BomSnapshot bom = bomMap.get(itemCode);
    calcItem.setOaNo(oaNo);
    calcItem.setBusinessUnitType(businessUnitType);
    calcItem.setPricingMonth(pricingMonth);
    calcItem.setPriceAsOfTime(priceAsOfTime);
    calcItem.setCalcScene(LinkedPriceCalcScene.QUOTE.getCode());
    calcItem.setFactorSource(factorSource);
    calcItem.setAdjustBatchId(null);
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(bom == null ? null : bom.shapeAttr);
    calcItem.setBomQty(bom == null ? null : bom.bomQty);
  }

  private void populateMonthlyAdjustContext(
      PriceLinkedCalcItem calcItem,
      Long adjustBatchId,
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String itemCode) {
    // 月度调价联动价按“调价批次 + 月份 + 料号”固化，不绑定具体 OA 单。
    calcItem.setOaNo(null);
    calcItem.setBusinessUnitType(businessUnitType);
    calcItem.setPricingMonth(pricingMonth);
    calcItem.setPriceAsOfTime(priceAsOfTime);
    calcItem.setCalcScene(LinkedPriceCalcScene.MONTHLY_ADJUST.getCode());
    calcItem.setFactorSource(monthlyFactorSource(adjustBatchId));
    calcItem.setAdjustBatchId(adjustBatchId);
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(null);
    calcItem.setBomQty(null);
  }

  private void persist(PriceLinkedCalcItem calcItem, boolean created) {
    if (created) {
      priceLinkedCalcItemMapper.insert(calcItem);
    } else {
      priceLinkedCalcItemMapper.updateById(calcItem);
    }
  }

  private String monthlyFactorSource(Long adjustBatchId) {
    return adjustBatchId == null
        ? LinkedPriceFactorSource.MONTHLY_FACTOR.getCode()
        : LinkedPriceFactorSource.ADJUST_BATCH.getCode();
  }

  private String quoteFactorSource(LinkedPriceEnsureRequest request) {
    return request != null
        && request.getPriceScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE
        ? LinkedPriceFactorSource.FINANCE_QUOTE_BASE.getCode()
        : LinkedPriceFactorSource.OA_LOCKED.getCode();
  }

  private LocalDateTime resolvedPriceAsOfTime(LinkedPriceEnsureRequest request) {
    if (request != null && request.getPriceAsOfTime() != null) {
      return request.getPriceAsOfTime();
    }
    try {
      return YearMonth.parse(request.getPricingMonth().trim())
          .atEndOfMonth()
          .atTime(LocalTime.MAX);
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new IllegalArgumentException("pricingMonth 必须是 yyyy-MM，无法确定联动价取价日");
    }
  }

  private static class BomSnapshot {
    private BigDecimal bomQty;
    private String shapeAttr;
  }

  private record LinkedPriceCandidate(
      PriceLinkedItem row,
      SupplierPreferredPriceSelection<PriceLinkedItem> selection) {
  }
}
