package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.config.CostRunExecutionProperties;
import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import com.sanhua.marketingcost.dto.CostRunTrialResponse;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.CostRunBatch;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.CostRunBatchStatus;
import com.sanhua.marketingcost.enums.CostRunExecutionMode;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunBatchMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.CostRunTaskProgressService;
import com.sanhua.marketingcost.service.CostRunTaskSubmissionService;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.CostRunTrialService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
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
  private final CostRunBatchMapper costRunBatchMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunEngine costRunEngine;
  private final CostRunResultWriter costRunResultWriter;
  private final CostRunProgressStore progressStore;
  private final CostRunTaskSubmissionService taskSubmissionService;
  private final CostRunTaskProgressService taskProgressService;
  private final TransactionTemplate transactionTemplate;
  /** T15：主档同步入口，doRun 第一步调用 */
  private final MaterialMasterSyncService materialMasterSyncService;
  private final MaterialPriceRouterService materialPriceRouterService;
  /** LPE-08：实时成本只是 ensure 调用方，不拥有联动价准备能力。 */
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final QuoteBomStatusService quoteBomStatusService;
  private final CostRunExecutionProperties executionProperties;
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
      CostRunBatchMapper costRunBatchMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunEngine costRunEngine,
      CostRunResultWriter costRunResultWriter,
      CostRunProgressStore progressStore,
      CostRunTaskSubmissionService taskSubmissionService,
      CostRunTaskProgressService taskProgressService,
      TransactionTemplate transactionTemplate,
      MaterialMasterSyncService materialMasterSyncService,
      MaterialPriceRouterService materialPriceRouterService,
      LinkedPriceEnsureService linkedPriceEnsureService,
      PricePrepareReadinessService pricePrepareReadinessService,
      QuoteBomStatusService quoteBomStatusService,
      CostRunExecutionProperties executionProperties) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunBatchMapper = costRunBatchMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunEngine = costRunEngine;
    this.costRunResultWriter = costRunResultWriter;
    this.progressStore = progressStore;
    this.taskSubmissionService = taskSubmissionService;
    this.taskProgressService = taskProgressService;
    this.transactionTemplate = transactionTemplate;
    this.materialMasterSyncService = materialMasterSyncService;
    this.materialPriceRouterService = materialPriceRouterService;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
    this.quoteBomStatusService = quoteBomStatusService;
    this.executionProperties = executionProperties;
  }

  @Override
  @Async("costRunExecutor")
  public CompletableFuture<CostRunTrialResponse> run(String oaNo) {
    return run(oaNo, null, null);
  }

  @Override
  @Async("costRunExecutor")
  public CompletableFuture<CostRunTrialResponse> run(
      String oaNo, String username, String businessUnitType) {
    if (!StringUtils.hasText(oaNo)) {
      return CompletableFuture.completedFuture(new CostRunTrialResponse());
    }
    String oaNoValue = oaNo.trim();
    CostRunExecutionMode executionMode =
        resolveExecutionMode(oaNoValue, username, businessUnitType, true);
    if (executionMode == CostRunExecutionMode.TASK_WORKER) {
      return submitQuoteTask(oaNoValue, executionMode);
    }
    if (executionMode == CostRunExecutionMode.DUAL_COMPARE) {
      return runDualCompare(oaNoValue, executionMode);
    }
    return runApiSync(oaNoValue);
  }

  private CompletableFuture<CostRunTrialResponse> runApiSync(String oaNoValue) {
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

  private CompletableFuture<CostRunTrialResponse> submitQuoteTask(
      String oaNoValue, CostRunExecutionMode executionMode) {
    progressStore.enqueue(oaNoValue);
    try {
      ensureBomReadyForCostRun(oaNoValue);
      CostRunTaskSubmissionResult submission = taskSubmissionService.submitQuote(oaNoValue);
      CostRunTrialResponse response = responseFromSubmission(submission, executionMode);
      return CompletableFuture.completedFuture(response);
    } catch (RuntimeException ex) {
      progressStore.fail(oaNoValue, ex.getMessage());
      scheduleCleanup(oaNoValue);
      return CompletableFuture.failedFuture(ex);
    }
  }

  private CompletableFuture<CostRunTrialResponse> runDualCompare(
      String oaNoValue, CostRunExecutionMode executionMode) {
    progressStore.markRunning(oaNoValue);
    try {
      CostRunTrialResponse response = transactionTemplate.execute(status -> doRun(oaNoValue));
      CostRunTaskSubmissionResult submission = taskSubmissionService.submitQuote(oaNoValue);
      mergeSubmission(response, submission, executionMode);
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

    ensureBomReadyForCostRun(oaNoValue);

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
    for (OaFormItem item : formItems) {
      if (StringUtils.hasText(item.getMaterialNo())) {
        String code = item.getMaterialNo().trim();
        productCodes.add(code);
        productItems.putIfAbsent(code, item);
      }
    }

    // T16/T23：进度切片
    //   [0-5]    主档同步
    //   [5-10]   联动价 ensure（LPE-08）
    //   [10-60]  部品取价（50% 跨度，子进度按部品 i/N 累加）
    //   [60-95]  N 个产品的费用核算（35% 跨度，每产品 35/N）
    //   [95-100] saveOrUpdate + OA 状态更新
    final int PROGRESS_COSTS_END = 95;
    int productCount = Math.max(1, productCodes.size());

    int costItemCount = 0;
    int partItemCount = 0;
    int productIndex = 0;
    for (String productCode : productCodes) {
      final int idx = productIndex; // for lambda
      int productStart =
          PROGRESS_AFTER_LINKED_ENSURE
              + idx * (PROGRESS_COSTS_END - PROGRESS_AFTER_LINKED_ENSURE) / productCount;
      int productEnd =
          PROGRESS_AFTER_LINKED_ENSURE
              + (idx + 1) * (PROGRESS_COSTS_END - PROGRESS_AFTER_LINKED_ENSURE) / productCount;
      OaFormItem item = productItems.get(productCode);
      if (item != null) {
        CostRunContext context =
            CostRunContext.quote(
                oaNoValue,
                item.getId(),
                productCode,
                item.getPackageMethod(),
                form.getCustomer(),
                firstText(item.getBusinessUnitType(), form.getBusinessUnitType()),
                inferPeriod(LocalDate.now()),
                oaNoValue + ":" + productCode);
        // 普通 OA 的上传、主档同步、BOM 刷新和联动价准备仍在外层流程完成；
        // 这里开始只进入统一计算器，确保后续月度调价和日常报价复用同一套成本公式。
        context.setProgress(
            p -> progressStore.update(
                oaNoValue, productStart + p * (productEnd - productStart) / 100));
        CostRunObjectResult result = costRunEngine.run(context);
        costRunResultWriter.writeQuoteResult(result, form, item);
        partItemCount += result.getPartItems().size();
        costItemCount += result.getCostItems().size();
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
        new CostRunTrialResponse(productCodes.size(), partItemCount, costItemCount);
    response.setPricePrepareReadiness(pricePrepareReadiness);
    return response;
  }

  private void ensureBomReadyForCostRun(String oaNoValue) {
    QuoteBomStatusResponse response = quoteBomStatusService.checkForCostRun(oaNoValue);
    if (response == null || response.getItems() == null) {
      return;
    }
    for (QuoteBomStatusItemResponse item : response.getItems()) {
      if (item == null || isCostReadyBomStatus(item.getBomStatus())) {
        continue;
      }
      String productCode =
          StringUtils.hasText(item.getProductCode()) ? item.getProductCode().trim() : "-";
      String statusLabel = bomStatusLabel(item.getBomStatus());
      throw new RuntimeException(
          "产品 BOM 未准备完成：产品料号 "
              + productCode
              + "，状态 "
              + statusLabel
              + "，请先到“报价单产品 BOM 处理”处理。");
    }
  }

  private boolean isCostReadyBomStatus(String bomStatus) {
    return "SYNCED".equals(bomStatus)
        || "REUSED_CURRENT_MONTH".equals(bomStatus)
        || "MANUAL_ENTERED".equals(bomStatus);
  }

  private String bomStatusLabel(String bomStatus) {
    if ("SYNCING".equals(bomStatus)) {
      return "同步中";
    }
    if ("NO_BOM".equals(bomStatus)) {
      return "无BOM";
    }
    if ("CHECK_FAILED".equals(bomStatus)) {
      return "检查异常";
    }
    if ("ENTRY_IN_PROGRESS".equals(bomStatus)) {
      return "录入中";
    }
    if ("NOT_CHECKED".equals(bomStatus)) {
      return "未检查";
    }
    return StringUtils.hasText(bomStatus) ? bomStatus : "未知";
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
    return progress(oaNo, null, null);
  }

  @Override
  public CostRunProgressResponse progress(String oaNo, String username, String businessUnitType) {
    if (!StringUtils.hasText(oaNo)) {
      return progressStore.get(null);
    }
    String oaNoValue = oaNo.trim();
    if (resolveExecutionMode(oaNoValue, username, businessUnitType, false)
        == CostRunExecutionMode.TASK_WORKER) {
      CostRunProgressResponse dbProgress = latestQuoteBatchProgress(oaNoValue);
      if (dbProgress != null) {
        return dbProgress;
      }
    }
    return progressStore.get(oaNoValue);
  }

  private CostRunExecutionMode resolveExecutionMode(
      String oaNo, String username, String businessUnitType, boolean logDecision) {
    String effectiveBusinessUnitType = businessUnitType;
    if (executionProperties.hasGrayBusinessUnits()
        && !StringUtils.hasText(effectiveBusinessUnitType)
        && StringUtils.hasText(oaNo)) {
      effectiveBusinessUnitType = findOaBusinessUnitType(oaNo);
    }
    CostRunExecutionMode mode =
        executionProperties.resolveMode(username, effectiveBusinessUnitType);
    if (logDecision) {
      log.info(
          "cost run execution mode resolved: oaNo={} mode={} configuredMode={} user={} businessUnit={} grayUsers={} grayBusinessUnits={}",
          oaNo,
          mode.getCode(),
          executionProperties.getModeType().getCode(),
          normalizeForLog(username),
          normalizeForLog(effectiveBusinessUnitType),
          executionProperties.getGrayUsers().size(),
          executionProperties.getGrayBusinessUnits().size());
    }
    return mode;
  }

  private String findOaBusinessUnitType(String oaNo) {
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .select(OaForm::getId, OaForm::getBusinessUnitType)
                .eq(OaForm::getOaNo, oaNo)
                .last("LIMIT 1"));
    return form == null ? null : form.getBusinessUnitType();
  }

  private String normalizeForLog(String value) {
    return StringUtils.hasText(value) ? value.trim() : "-";
  }

  private CostRunProgressResponse latestQuoteBatchProgress(String oaNo) {
    CostRunBatch batch =
        costRunBatchMapper.selectOne(
            Wrappers.lambdaQuery(CostRunBatch.class)
                .eq(CostRunBatch::getScene, CostRunTaskScene.QUOTE.name())
                .eq(CostRunBatch::getSourceNo, oaNo)
                .orderByDesc(CostRunBatch::getCreatedAt)
                .orderByDesc(CostRunBatch::getId)
                .last("LIMIT 1"));
    if (batch == null) {
      return null;
    }
    CostRunBatchProgressSnapshot snapshot = taskProgressService.refreshBatchProgress(batch.getBatchNo());
    CostRunProgressResponse response = new CostRunProgressResponse();
    response.setPercent(snapshot.getProgress());
    response.setStatus(toLegacyProgressStatus(snapshot.getStatus()));
    response.setQueuePos(CostRunBatchStatus.PENDING.name().equals(snapshot.getStatus()) ? 1 : 0);
    return response;
  }

  private String toLegacyProgressStatus(String batchStatus) {
    CostRunBatchStatus status = CostRunBatchStatus.fromCode(batchStatus);
    return switch (status) {
      case PENDING -> "QUEUED";
      case RUNNING -> "RUNNING";
      case SUCCESS -> "DONE";
      case PARTIAL_FAILED, FAILED, CANCELED -> "ERROR";
    };
  }

  private CostRunTrialResponse responseFromSubmission(
      CostRunTaskSubmissionResult submission, CostRunExecutionMode executionMode) {
    CostRunTrialResponse response =
        new CostRunTrialResponse(submission.getTaskCount(), 0, 0);
    mergeSubmission(response, submission, executionMode);
    return response;
  }

  private void mergeSubmission(
      CostRunTrialResponse response,
      CostRunTaskSubmissionResult submission,
      CostRunExecutionMode executionMode) {
    response.setBatchNo(submission.getBatchNo());
    response.setExecutionMode(executionMode.getCode());
    response.setTaskStatus(submission.getStatus());
    response.setTaskCount(submission.getTaskCount());
    response.setSkippedCount(submission.getSkippedCount());
    response.setExistingBatch(submission.isExistingBatch());
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

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }
}
