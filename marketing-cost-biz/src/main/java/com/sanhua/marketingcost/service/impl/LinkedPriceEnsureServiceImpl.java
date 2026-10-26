package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.LinkedPriceFactorSource;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceSelectionService;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidenceFactory;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelection;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import com.sanhua.marketingcost.service.pricing.SupplierPriceScope;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
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
  static final String LINKED_RULE_ERROR = "LINKED_RULE_ERROR";
  private static final String CALC_STATUS_OK = "OK";
  private static final String CALC_STATUS_FAILED = "FAILED";

  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final OaFormMapper oaFormMapper;
  private final PriceLinkedCalcServiceImpl priceLinkedCalcService;
  private final SupplierPreferredPriceSelector supplierPreferredPriceSelector;
  private final MaterialPriceMonthSelector monthSelector;
  private final OriginalLinkedPriceRuntime originalRuntime;
  private final FinanceEffectivePriceService effectivePrices;
  private final FinanceEffectivePriceSelectionService effectiveSelection;
  private final SupplierPriceSelectionService supplierPrices;

  public LinkedPriceEnsureServiceImpl(
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      BomCostingRowMapper bomCostingRowMapper,
      OaFormMapper oaFormMapper,
      PriceLinkedCalcServiceImpl priceLinkedCalcService,
      SupplierPreferredPriceSelector supplierPreferredPriceSelector,
      MaterialPriceMonthSelector monthSelector,
      OriginalLinkedPriceRuntime originalRuntime,
      FinanceEffectivePriceService effectivePrices,
      FinanceEffectivePriceSelectionService effectiveSelection,
      SupplierPriceSelectionService supplierPrices) {
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.oaFormMapper = oaFormMapper;
    this.priceLinkedCalcService = priceLinkedCalcService;
    this.supplierPreferredPriceSelector = supplierPreferredPriceSelector;
    this.monthSelector = monthSelector;
    this.originalRuntime = originalRuntime;
    this.effectivePrices = effectivePrices;
    this.effectiveSelection = effectiveSelection;
    this.supplierPrices = supplierPrices;
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
    OaForm oaForm = request.getCalcScene() == LinkedPriceCalcScene.QUOTE ? fetchOaForm(oaNo) : null;
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate(),
            request.getPriceOrgCode(),
            request,
            oaForm);
    Map<String, BomSnapshot> bomMap = fetchBomSnapshots(oaNo, businessUnitType, itemCodes);
    List<PriceLinkedCalcItem> calculated = new ArrayList<>(itemCodes.size());
    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
      calcItem.setPriceOrgCode(request.getPriceOrgCode());
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
  @RetryOnConcurrency
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
    try {
      return ensureQuote(request, itemCodes, result);
    } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
      throw new org.springframework.dao.ConcurrencyFailureException(
          "联动价输入版本已由并发任务保存，重新读取并核对输入", concurrentInsert);
    }
  }

  private LinkedPriceEnsureResult ensureQuote(
      LinkedPriceEnsureRequest request, Set<String> itemCodes, LinkedPriceEnsureResult result) {
    String oaNo = request.getOaNo().trim();
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    LocalDateTime priceAsOfTime = resolvedPriceAsOfTime(request);
    String factorSource = quoteFactorSource(request);
    Map<String, List<PriceLinkedCalcItem>> existingMap =
        fetchExistingQuoteResults(
            oaNo,
            businessUnitType,
            pricingMonth,
            priceAsOfTime,
            factorSource,
            itemCodes,
            request.getPriceOrgCode());
    OaForm oaForm = request.getCalcScene() == LinkedPriceCalcScene.QUOTE ? fetchOaForm(oaNo) : null;
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate(),
            request.getPriceOrgCode(),
            request,
            oaForm);
    Map<String, BomSnapshot> bomMap = fetchBomSnapshots(oaNo, businessUnitType, itemCodes);

    List<PriceLinkedCalcItem> newResults = new ArrayList<>();
    for (String itemCode : itemCodes) {
      LinkedPriceCandidate candidate = linkedItemMap.get(itemCode);
      String fingerprint = quoteFingerprint(candidate, request, oaForm, bomMap.get(itemCode));
      var versions = existingMap.getOrDefault(itemCode, List.of());
      PriceLinkedCalcItem existing = matchingInput(versions, fingerprint);
      if (canSkip(existing, fingerprint, request.isForceRefresh())) {
        result.setSkippedCount(result.getSkippedCount() + 1);
        result.getItems().put(itemCode, existing);
        continue;
      }
      PriceLinkedCalcItem calcItem = new PriceLinkedCalcItem();
      calcItem.setPriceOrgCode(request.getPriceOrgCode());
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
      calcItem.setCalcFingerprint(
          CALC_STATUS_OK.equals(calcItem.getCalcStatus()) ? fingerprint : null);
      existing = matchingInput(versions, calcItem.getCalcFingerprint());
      if (existing != null && calcItem.getCalcFingerprint() != null) {
        // Forced recalculation still cannot rewrite a result already referenced by another quote.
        requireSameCalculation(existing, calcItem);
        result.setSkippedCount(result.getSkippedCount() + 1);
        result.getItems().put(itemCode, existing);
        continue;
      }
      if (existing == null) {
        newResults.add(calcItem);
        result.setCreatedCount(result.getCreatedCount() + 1);
      } else {
        // Legacy formulas and failed attempts have no complete fingerprint; keep their
        // established mutable slot and optimistic revision check, never reuse them as valid input.
        persistNewQuoteResults(newResults);
        newResults = new ArrayList<>();
        calcItem.setId(existing.getId());
        calcItem.setCreatedAt(existing.getCreatedAt());
        persist(
            calcItem,
            false,
            existing.getCalcFingerprint(),
            existing.getResultRevision() == null ? 0L : existing.getResultRevision());
        result.setUpdatedCount(result.getUpdatedCount() + 1);
      }
      result.getItems().put(itemCode, calcItem);
      if (CALC_STATUS_FAILED.equalsIgnoreCase(calcItem.getCalcStatus()))
        result.addFailedItem(itemCode, calcItem.getFailureCode(), calcItem.getCalcMessage());
    }
    persistNewQuoteResults(newResults);
    return result;
  }

  private PriceLinkedCalcItem matchingInput(
      List<PriceLinkedCalcItem> versions, String fingerprint) {
    return versions.stream()
        .filter(row -> java.util.Objects.equals(fingerprint, row.getCalcFingerprint()))
        .findFirst()
        .orElse(null);
  }

  private void requireSameCalculation(PriceLinkedCalcItem saved, PriceLinkedCalcItem calculated) {
    if (!CALC_STATUS_OK.equals(saved.getCalcStatus())
        || !sameNumber(saved.getPartUnitPrice(), calculated.getPartUnitPrice())
        || !sameStoredQuantity(saved.getBomQty(), calculated.getBomQty())
        || !sameNumber(saved.getPartAmount(), calculated.getPartAmount())
        || !java.util.Objects.equals(
            saved.getSourcePriceRecordId(), calculated.getSourcePriceRecordId())
        || !java.util.Objects.equals(saved.getSupplierCode(), calculated.getSupplierCode())) {
      throw new IllegalStateException("相同联动输入产生不同结果，保留原版本并停止发布：" + saved.getItemCode());
    }
  }

  private boolean sameNumber(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private boolean sameStoredQuantity(BigDecimal saved, BigDecimal calculated) {
    // BOM保留16位，结果表bom_qty只保留6位。按落库精度校验，金额仍使用完整BOM数量计算。
    // 完整数量已进入输入指纹，不能通过这里的舍入复用其他BOM输入。
    return sameNumber(
        saved, calculated == null ? null : calculated.setScale(6, java.math.RoundingMode.HALF_UP));
  }

  private void persistNewQuoteResults(List<PriceLinkedCalcItem> rows) {
    if (rows.isEmpty()) return;
    if (rows.size() == 1 && rows.getFirst().getCalcFingerprint() == null) {
      persist(rows.getFirst(), true, null, 0L);
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    for (var row : rows) {
      row.setResultRevision(0L);
      row.setCreatedAt(now);
      row.setUpdatedAt(now);
    }
    // 控制单条SQL和快照的大小；所有分批写入仍属于同一核算事务。
    for (int from = 0; from < rows.size(); from += 100) {
      var batch = rows.subList(from, Math.min(from + 100, rows.size()));
      var immutable = batch.stream().filter(row -> row.getCalcFingerprint() != null).toList();
      var mutable = batch.stream().filter(row -> row.getCalcFingerprint() == null).toList();
      if (!immutable.isEmpty()) {
        priceLinkedCalcItemMapper.insertOrReuseResults(immutable);
        var saved = priceLinkedCalcItemMapper.selectReusedResults(immutable).stream()
            .collect(java.util.stream.Collectors.toMap(PriceLinkedCalcItem::getItemCode, row -> row));
        for (var calculated : immutable) {
          var persisted = saved.get(calculated.getItemCode());
          if (persisted == null || !java.util.Objects.equals(
              persisted.getCalcFingerprint(), calculated.getCalcFingerprint())) {
            throw new IllegalStateException("联动价输入版本未完整写入，请重试");
          }
          requireSameCalculation(persisted, calculated);
          // 返回胜出版本的完整证据和 ID，不能把本地计算证据冒充成已保存版本。
          org.springframework.beans.BeanUtils.copyProperties(persisted, calculated);
        }
      }
      // 不完整指纹和失败结果不能按不可变版本复用，保留原冲突重试语义。
      if (!mutable.isEmpty() && priceLinkedCalcItemMapper.insertResults(mutable) != mutable.size())
        throw new IllegalStateException("联动价新结果未完整写入，请重试");
    }
  }

  private LinkedPriceEnsureResult ensureMonthlyAdjust(
      LinkedPriceEnsureRequest request, Set<String> itemCodes, LinkedPriceEnsureResult result) {
    String businessUnitType = request.getBusinessUnitType().trim();
    String pricingMonth = request.getPricingMonth().trim();
    LocalDateTime priceAsOfTime = resolvedPriceAsOfTime(request);
    Map<String, PriceLinkedCalcItem> existingMap =
        fetchExistingMonthlyAdjustResults(
            businessUnitType, pricingMonth, itemCodes, request.getPriceOrgCode());
    Map<String, LinkedPriceCandidate> linkedItemMap =
        fetchLinkedItems(
            businessUnitType,
            pricingMonth,
            itemCodes,
            priceAsOfTime.toLocalDate(),
            request.getPriceOrgCode());

    for (String itemCode : itemCodes) {
      PriceLinkedCalcItem existing = existingMap.get(itemCode);
      LinkedPriceCandidate candidate = linkedItemMap.get(itemCode);
      boolean created = existing == null;
      PriceLinkedCalcItem calcItem = created ? new PriceLinkedCalcItem() : existing;
      calcItem.setPriceOrgCode(request.getPriceOrgCode());
      populateMonthlyAdjustContext(
          calcItem, businessUnitType, pricingMonth, priceAsOfTime, itemCode);
      String previousFingerprint = existing == null ? null : existing.getCalcFingerprint();
      long previousRevision =
          existing == null || existing.getResultRevision() == null
              ? 0L
              : existing.getResultRevision();
      calculateMonthlyAdjustItem(calcItem, candidate, request);
      persist(calcItem, created, previousFingerprint, previousRevision);
      result.getItems().put(itemCode, calcItem);
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

  private Map<String, List<PriceLinkedCalcItem>> fetchExistingQuoteResults(
      String oaNo,
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String factorSource,
      Set<String> itemCodes,
      String priceOrgCode) {
    var query =
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getPriceOrgCode, text(priceOrgCode))
            .eq(PriceLinkedCalcItem::getSourceKind, "PUBLIC")
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
    Map<String, List<PriceLinkedCalcItem>> map = new LinkedHashMap<>();
    for (PriceLinkedCalcItem row : rows) {
      if (StringUtils.hasText(row.getItemCode())) {
        map.computeIfAbsent(row.getItemCode().trim(), ignored -> new ArrayList<>()).add(row);
      }
    }
    return map;
  }

  private Map<String, PriceLinkedCalcItem> fetchExistingMonthlyAdjustResults(
      String businessUnitType, String pricingMonth, Set<String> itemCodes, String priceOrgCode) {
    var query =
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getPriceOrgCode, text(priceOrgCode))
            .eq(PriceLinkedCalcItem::getSourceKind, "PUBLIC")
            .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.MONTHLY_ADJUST.getCode())
            .eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType)
            .eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth)
            .in(PriceLinkedCalcItem::getItemCode, itemCodes);
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
      String businessUnitType,
      String pricingMonth,
      Set<String> itemCodes,
      LocalDate priceDate,
      String requestedOrgCode) {
    return fetchLinkedItems(
        businessUnitType, pricingMonth, itemCodes, priceDate, requestedOrgCode, null, null);
  }

  private Map<String, LinkedPriceCandidate> fetchLinkedItems(
      String businessUnitType,
      String pricingMonth,
      Set<String> itemCodes,
      LocalDate priceDate,
      String requestedOrgCode,
      LinkedPriceEnsureRequest request,
      OaForm oaForm) {
    var query =
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getDeleted, 0)
            .eq(PriceLinkedItem::getSourceKind, "PUBLIC")
            .eq(PriceLinkedItem::getBusinessUnitType, businessUnitType)
            .in(PriceLinkedItem::getRuleStatus, List.of("LEGACY", "PUBLISHED"))
            .eq(
                StringUtils.hasText(requestedOrgCode),
                PriceLinkedItem::getOrgCode,
                requestedOrgCode)
            .le(PriceLinkedItem::getPricingMonth, pricingMonth)
            .in(PriceLinkedItem::getMaterialCode, itemCodes);
    // 选月后由 currentSupplierFormulas 按完整身份和修订排序；数据库无需再排序所有历史底稿。
    List<PriceLinkedItem> rows = priceLinkedItemMapper.selectPricingCandidates(query);
    Map<String, List<PriceLinkedItem>> candidatesByMaterial = new LinkedHashMap<>();
    for (PriceLinkedItem row : rows) {
      if (StringUtils.hasText(row.getMaterialCode())) {
        candidatesByMaterial
            .computeIfAbsent(row.getMaterialCode().trim(), key -> new ArrayList<>())
            .add(row);
      }
    }
    Map<String, LinkedPriceCandidate> map = new LinkedHashMap<>();
    Map<String, PriceMonthSelection> monthsByOrg = new HashMap<>();
    Map<String, List<PriceLinkedItem>> currentCandidates = new LinkedHashMap<>();
    Set<String> needsSupplierSelection = new TreeSet<>();
    for (var entry : candidatesByMaterial.entrySet()) {
      var orgs = entry.getValue().stream().map(row -> text(row.getOrgCode())).distinct().toList();
      if (orgs.size() != 1) continue;
      var months =
          monthsByOrg.computeIfAbsent(
              orgs.getFirst(), org -> monthSelector.select(businessUnitType, org, pricingMonth));
      if (months == null) continue;
      var current =
          currentSupplierFormulas(
              entry.getValue().stream()
                  .filter(row -> months.sourceMonth().equals(row.getPricingMonth()))
                  .toList());
      currentCandidates.put(entry.getKey(), current);
      if ((request == null
              || current.stream()
                  .anyMatch(
                      row ->
                          !OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())))
          && current.stream()
                  .map(this::linkedSupplierKey)
                  .filter(key -> !"NO_SUPPLIER".equals(key))
                  .distinct()
                  .count()
              > 1) needsSupplierSelection.add(entry.getKey());
    }
    Map<String, com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult> supplierInputs =
        needsSupplierSelection.isEmpty()
            ? Map.of()
            : supplierPreferredPriceSelector.prepareSuppliers(
                businessUnitType, needsSupplierSelection, priceDate);
    var effectiveByOriginal = new HashMap<Long, FinanceEffectivePrice>();
    var effectiveErrors = new HashMap<String, String>();
    var nativeByOrg = new LinkedHashMap<String, List<PriceLinkedItem>>();
    if (request != null)
      currentCandidates.values().stream()
          .flatMap(List::stream)
          .filter(row -> OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion()))
          .forEach(
              row ->
                  nativeByOrg
                      .computeIfAbsent(row.getOrgCode(), ignored -> new ArrayList<>())
                      .add(row));
    for (var group : nativeByOrg.entrySet()) {
      try {
        var financial =
            originalRuntime
                .context(group.getValue().getFirst(), pricingMonth, null, oaForm)
                .financial();
        effectiveByOriginal.putAll(
            effectivePrices.read(
                "LINKED",
                group.getValue().stream().map(PriceLinkedItem::getId).toList(),
                financial));
      } catch (IllegalArgumentException failure) {
        effectiveErrors.put(group.getKey(), failure.getMessage());
      }
    }
    Set<String> nativeMultiple = new TreeSet<>();
    currentCandidates.forEach(
        (code, values) -> {
          if (request != null
              && values.size() > 1
              && values.stream()
                  .allMatch(
                      row -> OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())))
            nativeMultiple.add(code);
        });
    var nativeRatios =
        nativeMultiple.isEmpty()
            ? Map.<String, List<SupplierSupplyRatio>>of()
            : supplierPrices.prepareRatios(businessUnitType, nativeMultiple, priceDate);
    for (Map.Entry<String, List<PriceLinkedItem>> entry : candidatesByMaterial.entrySet()) {
      var organizations =
          entry.getValue().stream()
              .map(row -> row.getOrgCode() == null ? "" : row.getOrgCode().trim())
              .distinct()
              .toList();
      if (organizations.size() != 1) {
        map.put(
            entry.getKey(),
            selectionFailure(
                "PRICE_ORGANIZATION_REQUIRED", "料号=" + entry.getKey() + "存在多个取价组织，必须明确组织后取价"));
        continue;
      }
      String organization = organizations.getFirst();
      PriceMonthSelection months =
          monthsByOrg.computeIfAbsent(
              organization, org -> monthSelector.select(businessUnitType, org, pricingMonth));
      if (months == null) {
        map.put(
            entry.getKey(),
            selectionFailure(
                MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
                "料号=" + entry.getKey() + "，核算月=" + pricingMonth + "：没有适用原稿月份"));
        continue;
      }
      List<PriceLinkedItem> candidates = currentCandidates.getOrDefault(entry.getKey(), List.of());
      if (candidates.isEmpty()) {
        // 新月已改为行情区间时允许尝试该月区间；不再读取历史联动成功缓存。
        if (!monthSelector.hasRange(
            businessUnitType, organization, months.sourceMonth(), entry.getKey())) {
          map.put(
              entry.getKey(),
              selectionFailure(
                  MaterialPriceMonthSelector.MATERIAL_MONTH_PRICE_MISSING,
                  MaterialPriceMonthSelector.missingMessage(entry.getKey(), organization, months)));
        }
        continue;
      }
      if (request != null
          && candidates.stream()
              .anyMatch(
                  row -> OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion()))) {
        try {
          if (effectiveErrors.containsKey(organization))
            throw new IllegalArgumentException(effectiveErrors.get(organization));
          if (candidates.stream()
              .anyMatch(
                  row -> !OriginalLinkedRuleSnapshot.VERSION.equals(row.getExpressionVersion())))
            throw new IllegalArgumentException("同月混入原稿与旧公式，必须明确适用版本：" + entry.getKey());
          var saved =
              candidates.stream()
                  .map(
                      row ->
                          effectivePrices.require(
                              FinanceEffectivePriceService.SourceRule.linked(row),
                              effectiveByOriginal))
                  .toList();
          var reviews = request.getSupplierPriceReviewContext();
          var scope =
              new SupplierPriceScope(
                  businessUnitType,
                  request.getOaNo(),
                  reviews == null ? null : reviews.oaFormItemId(),
                  pricingMonth,
                  priceDate,
                  organization,
                  entry.getKey(),
                  "LINKED");
          var chosen =
              effectiveSelection.select(
                  scope, saved, reviews, nativeRatios.getOrDefault(entry.getKey(), List.of()));
          if (chosen.price() == null) {
            map.put(
                entry.getKey(),
                selectionFailure(
                    SupplierPriceSelectionService.REVIEW_REQUIRED, chosen.supplier().message()));
          } else {
            var row =
                candidates.stream()
                    .filter(r -> r.getId().equals(chosen.price().getOriginalId()))
                    .findFirst()
                    .orElseThrow();
            var ratio = chosen.supplier().ratio();
            var selection =
                new SupplierPreferredPriceSelection<>(
                    row,
                    chosen.supplier().message(),
                    candidates.size(),
                    row.getSupplierName(),
                    row.getSupplierCode(),
                    ratio == null ? null : ratio.getSupplyRatio(),
                    ratio == null ? null : ratio.getId(),
                    chosen.supplier().rule(),
                    null,
                    null);
            map.put(entry.getKey(), new LinkedPriceCandidate(row, selection, chosen.price()));
          }
        } catch (IllegalArgumentException | IllegalStateException failure) {
          map.put(entry.getKey(), selectionFailure(LINKED_RULE_ERROR, failure.getMessage()));
        }
        continue;
      }
      PriceLinkedItem first = candidates.getFirst();
      SupplierPreferredPriceSelection<PriceLinkedItem> selection =
          supplierPreferredPriceSelector.select(
              candidates,
              businessUnitType,
              entry.getKey(),
              first.getMaterialName(),
              first.getSpecModel(),
              priceDate,
              PriceLinkedItem::getSupplierName,
              PriceLinkedItem::getSupplierCode,
              supplierInputs.get(
                  com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(
                      entry.getKey())));
      if (selection.row() != null
          && candidates.stream()
                  .filter(
                      row ->
                          selection.matchesSupplier(row.getSupplierName(), row.getSupplierCode()))
                  .count()
              > 1) {
        map.put(
            entry.getKey(),
            selectionFailure(
                "MATERIAL_RULE_AMBIGUOUS",
                "料号="
                    + entry.getKey()
                    + "，供应商="
                    + selection.mainSupplierCode()
                    + "存在多个规格或计价单位的规则，必须明确适用身份"));
      } else {
        map.put(entry.getKey(), new LinkedPriceCandidate(selection.row(), selection));
      }
    }
    return map;
  }

  private LinkedPriceCandidate selectionFailure(String code, String message) {
    return new LinkedPriceCandidate(
        null,
        new SupplierPreferredPriceSelection<>(
            null, "", 0, null, null, null, null, "", code, message));
  }

  /** 已固定范围月份后，同一完整业务身份取最新修订；历史导入行按导入时间排序。 */
  private List<PriceLinkedItem> currentSupplierFormulas(List<PriceLinkedItem> candidates) {
    return candidates == null ? List.of()
        : com.sanhua.marketingcost.service.pricing.LinkedPriceRuleVersions.current(candidates);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private String linkedSupplierKey(PriceLinkedItem item) {
    String supplierCode =
        SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
            item == null ? null : item.getSupplierCode());
    if (StringUtils.hasText(supplierCode)) {
      return "CODE:" + supplierCode;
    }
    String supplierName =
        SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(
            item == null ? null : item.getSupplierName());
    return StringUtils.hasText(supplierName) ? "NAME:" + supplierName : "NO_SUPPLIER";
  }

  private Map<String, BomSnapshot> fetchBomSnapshots(
      String oaNo, String businessUnitType, Set<String> itemCodes) {
    List<BomCostingRow> rows =
        bomCostingRowMapper.selectList(
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
        snapshot.bomQty =
            snapshot.bomQty == null ? row.getQtyPerTop() : snapshot.bomQty.add(row.getQtyPerTop());
      }
      if (!StringUtils.hasText(snapshot.shapeAttr) && StringUtils.hasText(row.getShapeAttr())) {
        snapshot.shapeAttr = row.getShapeAttr().trim();
      }
    }
    return map;
  }

  private OaForm fetchOaForm(String oaNo) {
    return oaFormMapper.selectOne(
        Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
  }

  private boolean canSkip(PriceLinkedCalcItem existing, String fingerprint, boolean forceRefresh) {
    return existing != null
        && !forceRefresh
        && fingerprint != null
        && fingerprint.equals(existing.getCalcFingerprint())
        && CALC_STATUS_OK.equals(existing.getCalcStatus())
        && existing.getPartUnitPrice() != null;
  }

  private String quoteFingerprint(
      LinkedPriceCandidate candidate,
      LinkedPriceEnsureRequest request,
      OaForm oaForm,
      BomSnapshot bom) {
    if (candidate == null
        || candidate.row() == null
        || candidate.effective() == null
        || candidate.selection().failed()
        || !OriginalLinkedRuleSnapshot.VERSION.equals(candidate.row().getExpressionVersion()))
      return null;
    try {
      BigDecimal copper =
          request.getPriceScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE
                  || oaForm == null
              ? null
              : oaForm.getCopperPrice();
      String input =
          candidate.effective().getInputFingerprint()
              + "|"
              + request.getPricingMonth()
              + "|"
              + copper
              + "|"
              + (oaForm == null
                  ? ""
                  : oaForm.getSus304Price()
                      + ":"
                      + oaForm.getSus316lPrice()
                      + ":"
                      + oaForm.getBoardTender());
      var selection = candidate.selection();
      // 公式本身不依赖BOM数量，但这里保存的金额和供应商依据也必须匹配本次输入。
      String detail =
          input
              + "|"
              + selection.matchMode()
              + "|"
              + selection.traceMessage()
              + "|"
              + selection.mainSupplierCode()
              + "|"
              + selection.mainSupplierName()
              + "|"
              + selection.supplyRatioRecordId()
              + "|"
              + selection.supplyRatio()
              + "|"
              + (bom == null ? "" : bom.bomQty)
              + "|"
              + (bom == null ? "" : bom.shapeAttr);
      return PriceLinkedImportFileDigest.sha256(
          detail.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IllegalArgumentException invalidInput) {
      // 失效输入不能命中缓存；正式计算负责返回带料号与原格的具体错误。
      return null;
    }
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
      if (candidate != null && candidate.effective() != null) {
        BigDecimal copper =
            request.getPriceScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE
                    || oaForm == null
                ? null
                : oaForm.getCopperPrice();
        var response =
            originalRuntime.previewSaved(
                linkedItem,
                request.getPricingMonth(),
                copper,
                oaForm,
                candidate.effective(),
                effectivePrices.calculation(candidate.effective()));
        priceLinkedCalcService.applyEffectiveQuotePrice(calcItem, linkedItem, response);
      } else if (request.getPriceScenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE) {
        priceLinkedCalcService.calculateQuoteItemForEnsure(
            calcItem, linkedItem, oaForm, request.normalizedVariableOverrides(), factorSource,
            request.getSupplierPriceReviewContext());
      } else {
        priceLinkedCalcService.calculateQuoteItemForEnsure(
            calcItem, linkedItem, oaForm, request.getSupplierPriceReviewContext());
      }
      applySelectionEvidence(calcItem, candidate);
      applyFormulaCarryForwardWarning(calcItem);
      if (linkedItem != null && CALC_STATUS_FAILED.equals(calcItem.getCalcStatus())) {
        calcItem.setFailureCode(LINKED_RULE_ERROR);
      }
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException ex) {
      markFailed(calcItem, LINKED_RULE_ERROR, ex.getMessage());
    }
  }

  private void calculateMonthlyAdjustItem(
      PriceLinkedCalcItem calcItem, LinkedPriceCandidate candidate, LinkedPriceEnsureRequest request) {
    if (applySelectionFailure(calcItem, candidate)) {
      return;
    }
    try {
      priceLinkedCalcService.calculateMonthlyAdjustItemForEnsure(
          calcItem, candidate == null ? null : candidate.row(), request.getSupplierPriceReviewContext());
      applySelectionEvidence(calcItem, candidate);
      applyFormulaCarryForwardWarning(calcItem);
      if (candidate != null
          && candidate.row() != null
          && CALC_STATUS_FAILED.equals(calcItem.getCalcStatus())) {
        calcItem.setFailureCode(LINKED_RULE_ERROR);
      }
    } catch (org.springframework.dao.DataAccessException databaseFailure) {
      throw databaseFailure;
    } catch (RuntimeException ex) {
      markFailed(calcItem, LINKED_RULE_ERROR, ex.getMessage());
    }
  }

  private boolean applySelectionFailure(
      PriceLinkedCalcItem calcItem, LinkedPriceCandidate candidate) {
    if (candidate == null || !candidate.selection().failed()) {
      return false;
    }
    markFailed(
        calcItem, candidate.selection().failureCode(), candidate.selection().failureMessage());
    return true;
  }

  private void markFailed(PriceLinkedCalcItem calcItem, String failureCode, String message) {
    clearSelectionEvidence(calcItem);
    calcItem.setPartUnitPrice(null);
    calcItem.setPartAmount(null);
    calcItem.setTraceJson(null);
    calcItem.setInputSnapshotJson(null);
    calcItem.setCalcFingerprint(null);
    calcItem.setCalculatorVersion(null);
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
    LocalDate pricingDate =
        calcItem.getPriceAsOfTime() == null ? null : calcItem.getPriceAsOfTime().toLocalDate();
    PriceResolveEvidence evidence =
        PriceResolveEvidenceFactory.create(
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
    var ruleMonths = new PriceMonthSelection(calcItem.getPricingMonth(), row.getPricingMonth());
    calcItem.setCarriedForward(ruleMonths.carriedForward() ? 1 : 0);
    calcItem.setWarningMessage(
        ruleMonths.carriedForward()
            ? "原稿沿用历史月份：核算月=" + ruleMonths.requestedMonth() + "，规则月=" + ruleMonths.sourceMonth()
            : evidence.warningMessage());
    if ("FALLBACK_HIGH".equals(selection.matchMode())) {
      calcItem.setWarningMessage(
          (calcItem.getWarningMessage() == null ? "" : calcItem.getWarningMessage() + "；")
              + selection.traceMessage());
    }
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
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(bom == null ? null : bom.shapeAttr);
    calcItem.setBomQty(bom == null ? null : bom.bomQty);
  }

  private void populateMonthlyAdjustContext(
      PriceLinkedCalcItem calcItem,
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
    calcItem.setFactorSource(LinkedPriceFactorSource.MONTHLY_FACTOR.getCode());
    calcItem.setItemCode(itemCode);
    calcItem.setShapeAttr(null);
    calcItem.setBomQty(null);
  }

  private void persist(
      PriceLinkedCalcItem calcItem,
      boolean created,
      String previousFingerprint,
      long previousRevision) {
    calcItem.setResultRevision(created ? 0L : previousRevision + 1);
    calcItem.setUpdatedAt(LocalDateTime.now());
    if (created) {
      priceLinkedCalcItemMapper.insert(calcItem);
    } else {
      // MyBatis默认忽略NULL更新；缺价/恢复时必须清除上一轮的价格、失败码和来源证据。
      int updated =
          priceLinkedCalcItemMapper.update(
              calcItem,
              Wrappers.<PriceLinkedCalcItem>lambdaUpdate()
                  .eq(PriceLinkedCalcItem::getId, calcItem.getId())
                  .eq(PriceLinkedCalcItem::getResultRevision, previousRevision)
                  .eq(
                      previousFingerprint != null,
                      PriceLinkedCalcItem::getCalcFingerprint,
                      previousFingerprint)
                  .isNull(previousFingerprint == null, PriceLinkedCalcItem::getCalcFingerprint)
                  .set(
                      calcItem.getPartUnitPrice() == null,
                      PriceLinkedCalcItem::getPartUnitPrice,
                      null)
                  .set(calcItem.getPartAmount() == null, PriceLinkedCalcItem::getPartAmount, null)
                  .set(
                      calcItem.getSourcePriceRecordId() == null,
                      PriceLinkedCalcItem::getSourcePriceRecordId,
                      null)
                  .set(
                      calcItem.getSourcePriceBatchNo() == null,
                      PriceLinkedCalcItem::getSourcePriceBatchNo,
                      null)
                  .set(
                      calcItem.getSupplierName() == null,
                      PriceLinkedCalcItem::getSupplierName,
                      null)
                  .set(
                      calcItem.getSupplierCode() == null,
                      PriceLinkedCalcItem::getSupplierCode,
                      null)
                  .set(calcItem.getSupplyRatio() == null, PriceLinkedCalcItem::getSupplyRatio, null)
                  .set(
                      calcItem.getSupplyRatioRecordId() == null,
                      PriceLinkedCalcItem::getSupplyRatioRecordId,
                      null)
                  .set(
                      calcItem.getSourceEffectiveFrom() == null,
                      PriceLinkedCalcItem::getSourceEffectiveFrom,
                      null)
                  .set(
                      calcItem.getSourceEffectiveTo() == null,
                      PriceLinkedCalcItem::getSourceEffectiveTo,
                      null)
                  .set(
                      calcItem.getWarningMessage() == null,
                      PriceLinkedCalcItem::getWarningMessage,
                      null)
                  .set(calcItem.getFailureCode() == null, PriceLinkedCalcItem::getFailureCode, null)
                  .set(calcItem.getTraceJson() == null, PriceLinkedCalcItem::getTraceJson, null)
                  .set(
                      calcItem.getInputSnapshotJson() == null,
                      PriceLinkedCalcItem::getInputSnapshotJson,
                      null)
                  .set(
                      calcItem.getCalcFingerprint() == null,
                      PriceLinkedCalcItem::getCalcFingerprint,
                      null)
                  .set(
                      calcItem.getCalculatorVersion() == null,
                      PriceLinkedCalcItem::getCalculatorVersion,
                      null));
      if (updated != 1)
        throw new org.springframework.dao.ConcurrencyFailureException(
            "联动价输入已被其他任务更新，本次旧结果不写回，请重试：" + calcItem.getItemCode());
    }
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
      return YearMonth.parse(request.getPricingMonth().trim()).atEndOfMonth().atTime(LocalTime.MAX);
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
      SupplierPreferredPriceSelection<PriceLinkedItem> selection,
      FinanceEffectivePrice effective) {
    LinkedPriceCandidate(
        PriceLinkedItem row, SupplierPreferredPriceSelection<PriceLinkedItem> selection) {
      this(row, selection, null);
    }
  }
}
