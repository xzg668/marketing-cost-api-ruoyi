package com.sanhua.marketingcost.worker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareReadinessService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class QuoteCostRunTaskExecutor implements CostRunTaskExecutor {

  private static final Logger log = LoggerFactory.getLogger(QuoteCostRunTaskExecutor.class);
  private static final int PROGRESS_AFTER_SYNC = 5;
  private static final int PROGRESS_AFTER_LINKED_ENSURE = 10;
  private static final int PROGRESS_COSTS_END = 95;

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunTaskMapper costRunTaskMapper;
  private final MaterialMasterSyncService materialMasterSyncService;
  private final PricePrepareReadinessService pricePrepareReadinessService;
  private final MaterialPriceRouterService materialPriceRouterService;
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final CostRunEngine costRunEngine;
  private final CostRunResultWriter costRunResultWriter;

  public QuoteCostRunTaskExecutor(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunTaskMapper costRunTaskMapper,
      MaterialMasterSyncService materialMasterSyncService,
      PricePrepareReadinessService pricePrepareReadinessService,
      MaterialPriceRouterService materialPriceRouterService,
      LinkedPriceEnsureService linkedPriceEnsureService,
      CostRunEngine costRunEngine,
      CostRunResultWriter costRunResultWriter) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunTaskMapper = costRunTaskMapper;
    this.materialMasterSyncService = materialMasterSyncService;
    this.pricePrepareReadinessService = pricePrepareReadinessService;
    this.materialPriceRouterService = materialPriceRouterService;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.costRunEngine = costRunEngine;
    this.costRunResultWriter = costRunResultWriter;
  }

  @Override
  public CostRunTaskScene scene() {
    return CostRunTaskScene.QUOTE;
  }

  @Override
  @Transactional
  public CostRunTaskExecutionResult execute(CostRunTask task, String workerId) {
    String oaNo = required("oaNo", task.getOaNo());

    syncMaterialMaster(oaNo, task, workerId);
    OaForm form = loadForm(oaNo);
    OaFormItem item = loadItem(task, form);
    String pricingMonth = pricingMonth(task);
    checkPricePrepareReadiness(oaNo, pricingMonth);
    ensureLinkedPricesForTrial(oaNo, form, pricingMonth, task, workerId);

    CostRunContext context =
        CostRunContext.quote(
            oaNo,
            item.getId(),
            productCode(task, item),
            firstText(task.getPackageMethod(), item.getPackageMethod()),
            firstText(task.getCustomerName(), form.getCustomer()),
            firstText(item.getBusinessUnitType(), form.getBusinessUnitType(), task.getBusinessUnitType()),
            pricingMonth,
            task.getPriceAsOfTime(),
            firstText(task.getCalcObjectKey(), "QUOTE:" + item.getId()));
    context.setProgress(
        p -> updateTaskProgress(task, workerId, PROGRESS_AFTER_LINKED_ENSURE
            + boundedProgress(p) * (PROGRESS_COSTS_END - PROGRESS_AFTER_LINKED_ENSURE) / 100));
    CostRunObjectResult result = costRunEngine.run(context);
    costRunResultWriter.writeQuoteResult(result, form, item);
    markItemCalculated(item);
    updateTaskProgress(task, workerId, PROGRESS_COSTS_END);
    updateOaCalculatedIfAllItemsDone(form);
    return new CostRunTaskExecutionResult(resultSummaryJson(result));
  }

  private void syncMaterialMaster(String oaNo, CostRunTask task, String workerId) {
    try {
      MaterialMasterSyncService.SyncResult syncResult = materialMasterSyncService.syncByOaNo(oaNo);
      log.info(
          "quote worker material sync done: taskId={} oa={} codes={} stagingHits={} affected={}",
          task.getId(),
          oaNo,
          syncResult.distinctCodes(),
          syncResult.stagingHits(),
          syncResult.affectedRows());
      updateTaskProgress(task, workerId, PROGRESS_AFTER_SYNC);
    } catch (RuntimeException ex) {
      throw new RuntimeException("主档同步失败: " + ex.getMessage(), ex);
    }
  }

  private OaForm loadForm(String oaNo) {
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
    if (form == null) {
      throw new IllegalArgumentException("OA单号不存在");
    }
    return form;
  }

  private OaFormItem loadItem(CostRunTask task, OaForm form) {
    OaFormItem item = null;
    if (task.getOaFormItemId() != null) {
      item = oaFormItemMapper.selectById(task.getOaFormItemId());
    }
    if (item == null && StringUtils.hasText(task.getProductCode())) {
      item =
          oaFormItemMapper.selectOne(
              Wrappers.lambdaQuery(OaFormItem.class)
                  .eq(OaFormItem::getOaFormId, form.getId())
                  .eq(OaFormItem::getMaterialNo, task.getProductCode().trim())
                  .last("LIMIT 1"));
    }
    if (item == null) {
      throw new IllegalArgumentException("OA单号未关联产品明细");
    }
    if (form.getId() != null && item.getOaFormId() != null && !form.getId().equals(item.getOaFormId())) {
      throw new IllegalArgumentException("任务产品明细不属于当前OA");
    }
    return item;
  }

  private String pricingMonth(CostRunTask task) {
    if (StringUtils.hasText(task.getPricingMonth())) {
      return task.getPricingMonth().trim();
    }
    return CostPricingPeriodUtils.currentPricingMonth();
  }

  private String productCode(CostRunTask task, OaFormItem item) {
    return required("productCode", firstText(task.getProductCode(), item.getMaterialNo()));
  }

  private void checkPricePrepareReadiness(String oaNo, String pricingMonth) {
    PricePrepareReadinessResult readiness = pricePrepareReadinessService.check(oaNo, pricingMonth);
    if (readiness != null && readiness.isWarning() && StringUtils.hasText(readiness.getMessage())) {
      log.warn(
          "quote worker price prepare readiness warning: oa={} period={} status={} message={}",
          oaNo,
          pricingMonth,
          readiness.getStatus(),
          readiness.getMessage());
    }
    if (readiness != null && readiness.isWarning()) {
      throw new RuntimeException(blockingReadinessMessage(readiness.getMessage()));
    }
  }

  private void ensureLinkedPricesForTrial(
      String oaNo, OaForm form, String pricingMonth, CostRunTask task, String workerId) {
    LocalDate quoteDate = CostPricingPeriodUtils.currentPricingDate();
    Set<String> linkedItemCodes = collectLinkedItemCodes(oaNo, quoteDate, pricingMonth);
    if (linkedItemCodes.isEmpty()) {
      log.info("quote worker linked ensure skipped: taskId={} oa={} no linked route", task.getId(), oaNo);
      updateTaskProgress(task, workerId, PROGRESS_AFTER_LINKED_ENSURE);
      return;
    }
    try {
      LinkedPriceEnsureResult result =
          linkedPriceEnsureService.ensure(
              LinkedPriceEnsureRequest.quote(
                  oaNo, form.getBusinessUnitType(), pricingMonth, linkedItemCodes));
      if (result.getFailedCount() > 0) {
        log.warn(
            "quote worker linked ensure failed but continue: taskId={} oa={} requested={} failed={} message={}",
            task.getId(),
            oaNo,
            result.getRequestedCount(),
            result.getFailedCount(),
            formatEnsureFailures(result));
        updateTaskProgress(task, workerId, PROGRESS_AFTER_LINKED_ENSURE);
        return;
      }
      log.info(
          "quote worker linked ensure done: taskId={} oa={} requested={} created={} updated={} skipped={}",
          task.getId(),
          oaNo,
          result.getRequestedCount(),
          result.getCreatedCount(),
          result.getUpdatedCount(),
          result.getSkippedCount());
      updateTaskProgress(task, workerId, PROGRESS_AFTER_LINKED_ENSURE);
    } catch (RuntimeException ex) {
      log.warn(
          "quote worker linked ensure exception but continue: taskId={} oa={}",
          task.getId(),
          oaNo,
          ex);
      updateTaskProgress(task, workerId, PROGRESS_AFTER_LINKED_ENSURE);
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
      String code =
          StringUtils.hasText(failedItem.getItemCode()) ? failedItem.getItemCode().trim() : "-";
      String reason =
          StringUtils.hasText(failedItem.getReason()) ? failedItem.getReason().trim() : "未知原因";
      messages.add(code + ": " + reason);
    }
    return messages.isEmpty() ? "存在联动价计算失败" : String.join("; ", messages);
  }

  private String blockingReadinessMessage(String message) {
    if (!StringUtils.hasText(message)) {
      return "当前月价格准备未完成，请先处理缺口后再重算";
    }
    return message
        .replace("实时成本将继续，", "已阻断实时成本，")
        + "；请先处理当前月价格准备缺口后再重算";
  }

  private void markItemCalculated(OaFormItem item) {
    if (item == null || item.getId() == null) {
      return;
    }
    oaFormItemMapper.markCalculated(item.getId(), LocalDateTime.now());
  }

  private void updateOaCalculatedIfAllItemsDone(OaForm form) {
    if (form == null || form.getId() == null) {
      return;
    }
    long runnableCount = oaFormItemMapper.countRunnableItems(form.getId());
    long calculatedCount = oaFormItemMapper.countCalculatedRunnableItems(form.getId());
    if (runnableCount <= 0 || calculatedCount < runnableCount) {
      return;
    }
    LocalDateTime calculatedAt = LocalDateTime.now();
    oaFormMapper.update(
        null,
        Wrappers.lambdaUpdate(OaForm.class)
            .set(OaForm::getCalcStatus, "已核算")
            .set(OaForm::getCalcAt, calculatedAt)
            .set(OaForm::getUpdatedAt, calculatedAt)
            .eq(OaForm::getId, form.getId()));
  }

  private void updateTaskProgress(CostRunTask task, String workerId, int progress) {
    if (task.getId() == null || !StringUtils.hasText(workerId)) {
      return;
    }
    costRunTaskMapper.updateProgress(
        task.getId(), workerId.trim(), boundedProgress(progress), LocalDateTime.now());
  }

  private String resultSummaryJson(CostRunObjectResult result) {
    int partItemCount = result.getPartItems() == null ? 0 : result.getPartItems().size();
    int costItemCount = result.getCostItems() == null ? 0 : result.getCostItems().size();
    String totalCost = result.getResult() == null || result.getResult().getTotalCost() == null
        ? null
        : result.getResult().getTotalCost().toPlainString();
    return "{\"partItemCount\":" + partItemCount
        + ",\"costItemCount\":" + costItemCount
        + ",\"totalCost\":" + (totalCost == null ? "null" : "\"" + totalCost + "\"")
        + "}";
  }

  private int boundedProgress(int progress) {
    return Math.max(0, Math.min(100, progress));
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private static String inferPeriod(LocalDate date) {
    return date.toString().substring(0, 7);
  }
}
