package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.CostRunTrialService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class CostRunTrialServiceImpl implements CostRunTrialService {
  private static final Logger log = LoggerFactory.getLogger(CostRunTrialServiceImpl.class);
  private static final long PROGRESS_CLEANUP_DELAY_MINUTES = 5;
  /** T15：主档同步完成时的进度节点（同步失败卡这里 + 红字） */
  private static final int PROGRESS_AFTER_SYNC = 5;
  /** LPE-08：联动价按需确保完成时的进度节点 */
  private static final int PROGRESS_AFTER_LINKED_ENSURE = 10;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunPartItemService costRunPartItemService;
  private final CostRunCostItemService costRunCostItemService;
  private final CostRunResultService costRunResultService;
  private final CostRunProgressStore progressStore;
  private final TransactionTemplate transactionTemplate;
  /** T15：主档同步入口，doRun 第一步调用 */
  private final MaterialMasterSyncService materialMasterSyncService;
  private final MaterialPriceRouterService materialPriceRouterService;
  /** LPE-08：实时成本只是 ensure 调用方，不拥有联动价准备能力。 */
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final ScheduledExecutorService cleanupScheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "progress-cleanup");
        t.setDaemon(true);
        return t;
      });

  @jakarta.annotation.PreDestroy
  void shutdownScheduler() {
    cleanupScheduler.shutdownNow();
  }

  public CostRunTrialServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunPartItemService costRunPartItemService,
      CostRunCostItemService costRunCostItemService,
      CostRunResultService costRunResultService,
      CostRunProgressStore progressStore,
      TransactionTemplate transactionTemplate,
      MaterialMasterSyncService materialMasterSyncService,
      MaterialPriceRouterService materialPriceRouterService,
      LinkedPriceEnsureService linkedPriceEnsureService,
      PricePrepareReadinessService pricePrepareReadinessService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunPartItemService = costRunPartItemService;
    this.costRunCostItemService = costRunCostItemService;
    this.costRunResultService = costRunResultService;
    this.progressStore = progressStore;
    this.transactionTemplate = transactionTemplate;
    this.materialMasterSyncService = materialMasterSyncService;
    this.materialPriceRouterService = materialPriceRouterService;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
  }

  @Override
  @Async("costRunExecutor")
  public CompletableFuture<CostRunTrialResponse> run(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CompletableFuture.completedFuture(new CostRunTrialResponse());
    }
    String oaNoValue = oaNo.trim();
    // T17：worker 接到 task → QUEUED 改 RUNNING（防重已在 controller.enqueue 做过）
    progressStore.markRunning(oaNoValue);
    try {
      CostRunTrialResponse response = transactionTemplate.execute(status -> doRun(oaNoValue));
      progressStore.complete(oaNoValue);
      scheduleCleanup(oaNoValue);
      return CompletableFuture.completedFuture(response);
    } catch (RuntimeException ex) {
      progressStore.fail(oaNoValue, ex.getMessage());
      scheduleCleanup(oaNoValue);
      return CompletableFuture.failedFuture(ex);
    }
  }

  private CostRunTrialResponse doRun(String oaNoValue) {
    // T15：第一步同步主档（独立子事务），失败抛出由 run() 的 catch 落到 progressStore.fail
    try {
      MaterialMasterSyncService.SyncResult syncResult =
          materialMasterSyncService.syncByOaNo(oaNoValue);
      log.info(
          "T15 sync done: oa={} codes={} stagingHits={} affected={}",
          oaNoValue, syncResult.distinctCodes(), syncResult.stagingHits(), syncResult.affectedRows());
      progressStore.update(oaNoValue, PROGRESS_AFTER_SYNC);
    } catch (RuntimeException syncEx) {
      log.error("T15 主档同步失败: oa={}", oaNoValue, syncEx);
      throw new RuntimeException("主档同步失败: " + syncEx.getMessage(), syncEx);
    }

    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNoValue).last("LIMIT 1"));
    if (form == null) {
      throw new RuntimeException("OA单号不存在");
    }

    List<OaFormItem> formItems =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class).eq(OaFormItem::getOaFormId, form.getId()));
    if (formItems.isEmpty()) {
      throw new RuntimeException("OA单号未关联产品明细");
    }

    PricePrepareReadinessResult pricePrepareReadiness =
        checkPricePrepareReadiness(oaNoValue, inferPeriod(LocalDate.now()));
    ensureLinkedPricesForTrial(oaNoValue, form);

    Set<String> productCodes = new LinkedHashSet<>();
    Map<String, OaFormItem> productItems = new LinkedHashMap<>();
    Map<String, Set<String>> materialCodesByProduct = new LinkedHashMap<>();
    for (OaFormItem item : formItems) {
      if (StringUtils.hasText(item.getMaterialNo())) {
        String code = item.getMaterialNo().trim();
        productCodes.add(code);
        productItems.putIfAbsent(code, item);
        materialCodesByProduct
            .computeIfAbsent(code, (key) -> new LinkedHashSet<>())
            .add(code);
      }
    }

    // T16/T23：进度切片
    //   [0-5]    主档同步
    //   [5-10]   联动价 ensure（LPE-08）
    //   [10-60]  部品取价（50% 跨度，子进度按部品 i/N 累加）
    //   [60-95]  N 个产品的费用核算（35% 跨度，每产品 35/N）
    //   [95-100] saveOrUpdate + OA 状态更新
    final int PROGRESS_PARTS_END = 60;
    final int PROGRESS_COSTS_END = 95;
    int productCount = Math.max(1, productCodes.size());

    List<CostRunPartItemDto> partItems =
        productCodes.isEmpty()
            ? Collections.emptyList()
            : costRunPartItemService.listByOaNo(
                oaNoValue,
                p -> progressStore.update(
                    oaNoValue,
                    PROGRESS_AFTER_LINKED_ENSURE
                        + p * (PROGRESS_PARTS_END - PROGRESS_AFTER_LINKED_ENSURE) / 100));
    progressStore.update(oaNoValue, PROGRESS_PARTS_END);

    int costItemCount = 0;
    int productIndex = 0;
    for (String productCode : productCodes) {
      final int idx = productIndex; // for lambda
      int productStart =
          PROGRESS_PARTS_END + idx * (PROGRESS_COSTS_END - PROGRESS_PARTS_END) / productCount;
      int productEnd =
          PROGRESS_PARTS_END + (idx + 1) * (PROGRESS_COSTS_END - PROGRESS_PARTS_END) / productCount;
      Set<String> materialCodes =
          materialCodesByProduct.getOrDefault(productCode, Collections.emptySet());
      List<CostRunCostItemDto> costItems =
          costRunCostItemService.listByMaterialCodes(
              oaNoValue, productCode, materialCodes,
              p -> progressStore.update(
                  oaNoValue, productStart + p * (productEnd - productStart) / 100));
      costItemCount += costItems.size();
      java.math.BigDecimal totalCost = null;
      for (CostRunCostItemDto item : costItems) {
        if ("TOTAL".equals(item.getCostCode())) {
          totalCost = item.getAmount();
          break;
        }
      }
      costRunResultService.updateTotalCost(oaNoValue, productCode, totalCost);
      OaFormItem item = productItems.get(productCode);
      if (item != null) {
        costRunResultService.saveOrUpdate(form, item);
      }
      productIndex++;
    }
    progressStore.update(oaNoValue, PROGRESS_COSTS_END);

    LocalDateTime calculatedAt = LocalDateTime.now();
    oaFormMapper.update(
        null,
        Wrappers.lambdaUpdate(OaForm.class)
            .set(OaForm::getCalcStatus, "已核算")
            .set(OaForm::getCalcAt, calculatedAt)
            .set(OaForm::getUpdatedAt, calculatedAt)
            .eq(OaForm::getId, form.getId()));
    CostRunTrialResponse response =
        new CostRunTrialResponse(productCodes.size(), partItems.size(), costItemCount);
    response.setPricePrepareReadiness(pricePrepareReadiness);
    return response;
  }

  private PricePrepareReadinessResult checkPricePrepareReadiness(String oaNo, String periodMonth) {
    PricePrepareReadinessResult readiness = pricePrepareReadinessService.check(oaNo, periodMonth);
    if (readiness != null && readiness.isWarning() && StringUtils.hasText(readiness.getMessage())) {
      progressStore.updateMessage(oaNo, readiness.getMessage());
      log.warn(
          "PPR-09 price prepare readiness warning: oa={} period={} status={} message={}",
          oaNo,
          periodMonth,
          readiness.getStatus(),
          readiness.getMessage());
    }
    if (readiness != null && !readiness.isAllowContinue()) {
      throw new RuntimeException(readiness.getMessage());
    }
    return readiness;
  }

  private void ensureLinkedPricesForTrial(String oaNo, OaForm form) {
    // 实时成本试算按当前月份取价，不按 OA.apply_date 回看历史月份。
    // 后续若固化试算基准日期，只需要把这里替换为统一日期来源。
    LocalDate quoteDate = LocalDate.now();
    String pricingMonth = inferPeriod(quoteDate);
    Set<String> linkedItemCodes = collectLinkedItemCodes(oaNo, quoteDate, pricingMonth);
    if (linkedItemCodes.isEmpty()) {
      log.info("LPE-08 realtime cost linked ensure skipped: oa={} no linked route", oaNo);
      progressStore.update(oaNo, PROGRESS_AFTER_LINKED_ENSURE);
      return;
    }

    try {
      LinkedPriceEnsureResult result =
          linkedPriceEnsureService.ensure(
              LinkedPriceEnsureRequest.quote(
                  oaNo, form.getBusinessUnitType(), pricingMonth, linkedItemCodes));
      if (result.getFailedCount() > 0) {
        throw new RuntimeException(formatEnsureFailures(result));
      }
      log.info(
          "LPE-08 realtime cost linked ensure done: oa={} requested={} created={} updated={} skipped={}",
          oaNo,
          result.getRequestedCount(),
          result.getCreatedCount(),
          result.getUpdatedCount(),
          result.getSkippedCount());
      progressStore.update(oaNo, PROGRESS_AFTER_LINKED_ENSURE);
    } catch (RuntimeException ensureEx) {
      log.error("LPE-08 实时成本联动价 ensure 失败: oa={}", oaNo, ensureEx);
      throw new RuntimeException("联动价按需确保失败: " + ensureEx.getMessage(), ensureEx);
    }
  }

  private Set<String> collectLinkedItemCodes(String oaNo, LocalDate quoteDate, String pricingMonth) {
    List<CostRunPartItemDto> baseItems = costRunPartItemMapper.selectBaseByOaNo(oaNo);
    if (baseItems == null || baseItems.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> linkedItemCodes = new LinkedHashSet<>();
    for (CostRunPartItemDto item : baseItems) {
      if (item == null || !StringUtils.hasText(item.getPartCode())) {
        continue;
      }
      String partCode = item.getPartCode().trim();
      List<PriceTypeRoute> candidates =
          materialPriceRouterService.listCandidates(partCode, pricingMonth, quoteDate);
      if (candidates == null || candidates.isEmpty()) {
        continue;
      }
      for (PriceTypeRoute route : candidates) {
        if (route != null && route.priceType() == PriceTypeEnum.LINKED) {
          linkedItemCodes.add(partCode);
          break;
        }
      }
    }
    return linkedItemCodes;
  }

  private String formatEnsureFailures(LinkedPriceEnsureResult result) {
    if (result.getFailedItems() == null || result.getFailedItems().isEmpty()) {
      return "存在联动价计算失败";
    }
    List<String> messages = new java.util.ArrayList<>();
    for (LinkedPriceEnsureResult.FailedItem failedItem : result.getFailedItems()) {
      if (failedItem == null) {
        continue;
      }
      String code = StringUtils.hasText(failedItem.getItemCode())
          ? failedItem.getItemCode().trim()
          : "-";
      String reason = StringUtils.hasText(failedItem.getReason())
          ? failedItem.getReason().trim()
          : "未知原因";
      messages.add(code + ": " + reason);
    }
    return messages.isEmpty() ? "存在联动价计算失败" : String.join("; ", messages);
  }

  @Override
  public CostRunProgressResponse progress(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return progressStore.get(null);
    }
    return progressStore.get(oaNo.trim());
  }

  /** 延迟清理进度记录，给前端足够时间轮询到最终状态。 */
  private void scheduleCleanup(String oaNo) {
    cleanupScheduler.schedule(
        () -> progressStore.remove(oaNo),
        PROGRESS_CLEANUP_DELAY_MINUTES,
        TimeUnit.MINUTES);
  }

  private static String inferPeriod(LocalDate date) {
    return date.toString().substring(0, 7);
  }
}
