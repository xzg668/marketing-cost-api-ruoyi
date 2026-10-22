package com.sanhua.marketingcost.service.electronicdrawing;

import static com.sanhua.marketingcost.service.electronicdrawing.DrawingReviewRejected.Reason.*;
import static com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationStore.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.DrawingVerificationResult;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationPayload;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 确认接收只提交证据及待消费状态；重试不改写证据，也不重做后续业务。 */
@Service
public class DrawingReviewService {
  private static final Logger log = LoggerFactory.getLogger(DrawingReviewService.class);
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingVerificationResultMapper results;
  private final DrawingVerificationStore store;
  private final OaFormItemMapper items;
  private final OaFormMapper forms;
  private final ObjectMapper json;
  private final TransactionTemplate transaction;

  public DrawingReviewService(
      DrawingVerificationTaskMapper tasks,
      DrawingVerificationResultMapper results,
      DrawingVerificationStore store,
      OaFormItemMapper items,
      OaFormMapper forms,
      ObjectMapper json,
      PlatformTransactionManager transactionManager) {
    this.tasks = tasks;
    this.results = results;
    this.store = store;
    this.items = items;
    this.forms = forms;
    this.json = json;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public Receipt receive(DrawingVerificationPayload payload) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("人工确认回执必须在独立接收事务提交后返回");
    }
    var known =
        tasks.findExternal(DrawingVerificationService.SOURCE_SYSTEM, payload.summary().taskId());
    if (known == null) throw new DrawingReviewRejected(UNKNOWN_TASK, "未找到已绑定的取图任务");
    Receipt receipt;
    try {
      receipt = Objects.requireNonNull(transaction.execute(status -> save(known, payload)));
    } catch (DrawingReviewRejected rejected) {
      throw rejected;
    } catch (RuntimeException failure) {
      log.error(
          "drawing review save failed: localTaskId={} externalTaskId={} exceptionType={}",
          known.getId(),
          known.getExternalTaskId(),
          failure.getClass().getSimpleName());
      throw failure;
    }
    // TransactionTemplate 已提交；后续消费者通过 current_result_id + consumption_state 恢复。
    log.info(
        "drawing review committed: localTaskId={} externalTaskId={} resultId={} duplicate={}"
            + " state={} consumptionState={}",
        receipt.localTaskId(),
        receipt.taskId(),
        receipt.resultId(),
        receipt.duplicate(),
        receipt.taskState(),
        receipt.consumptionState());
    return receipt;
  }

  private Receipt save(DrawingVerificationTask known, DrawingVerificationPayload payload) {
    // 与发起/首次响应保持相同锁顺序；锁定后再读取，避免并发回传读到旧快照。
    items.selectForCostingWrite(known.getOaFormItemId());
    var task = tasks.selectById(known.getId());
    if (task == null || !Objects.equals(task.getExternalTaskId(), payload.summary().taskId())) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "解析任务绑定已变化");
    }
    var previous = results.findEvent(task.getId(), MANUAL_CONFIRMATION);
    if (previous != null) {
      if (!previous.getContentFingerprint().equals(payload.contentFingerprint())) {
        throw new DrawingReviewRejected(CONTENT_CONFLICT, "该任务已接收不同内容的人工确认");
      }
      return receipt(task, previous, true);
    }
    validateConfirmation(payload);
    validateBinding(task, payload);
    var initial = results.findEvent(task.getId(), INITIAL);
    if (initial == null || payload.summary().revision() < initial.getUpstreamRevision()) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "首次取图来源缺失或确认版本早于首次核验");
    }
    String state = task.getActiveScopeKey() == null ? "SUPERSEDED" : "RECEIVED";
    var saved =
        store.append(
            task,
            MANUAL_CONFIRMATION,
            payload,
            initial.getId(),
            state,
            LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
    return receipt(tasks.selectById(task.getId()), saved, false);
  }

  private void validateConfirmation(DrawingVerificationPayload payload) {
    var summary = payload.summary();
    if (!"confirmed".equals(summary.status())
        || summary.confirmedAt() == null
        || summary.confirmedAt() <= 0) {
      throw new DrawingReviewRejected(INVALID_CONFIRMATION, "回传必须为带确认时间的人工确认摘要");
    }
    if (!summary.plmData().isEmpty()) {
      throw new DrawingReviewRejected(INVALID_CONFIRMATION, "人工确认的 plm_data 应为空数组，来源沿用首次核验");
    }
  }

  private void validateBinding(DrawingVerificationTask task, DrawingVerificationPayload payload) {
    if (!payload.summary().data().containsKey(task.getDrawingNo())) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "确认明细不包含该任务的产品图号");
    }
    var item = items.selectById(task.getOaFormItemId());
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null
        || !Objects.equals(form.getOaNo(), task.getOaNo())
        || !Objects.equals(form.getBusinessUnitType(), task.getBusinessUnitType())
        || !Objects.equals(item.getBusinessUnitType(), task.getBusinessUnitType())) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "取图任务与报价产品归属不一致");
    }
    // 核对任务自身的发起作用域；当前型号可能已修改，旧任务仍可以接收其历史。
    JsonNode input;
    try {
      input = json.readTree(task.getInputSnapshotJson());
    } catch (JsonProcessingException invalid) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "取图任务输入快照无效");
    }
    if (input == null
        || !input.path("oaFormItemId").isIntegralNumber()
        || input.path("oaFormItemId").longValue() != task.getOaFormItemId()
        || !Objects.equals(input.path("oaNo").asText(), task.getOaNo())
        || !Objects.equals(input.path("businessUnitType").asText(), task.getBusinessUnitType())
        || !Objects.equals(input.path("materialOrgCode").asText(), task.getMaterialOrgCode())
        || !Objects.equals(input.path("priceOrgCode").asText(), task.getPriceOrgCode())
        || !Objects.equals(input.path("accountingMonth").asText(), task.getAccountingMonth())
        || !Objects.equals(input.path("drawingNo").asText(), task.getDrawingNo())) {
      throw new DrawingReviewRejected(BINDING_CONFLICT, "取图任务与原报价作用域不一致");
    }
  }

  private Receipt receipt(
      DrawingVerificationTask task, DrawingVerificationResult result, boolean duplicate) {
    return new Receipt(
        task.getExternalTaskId(),
        task.getId(),
        result.getId(),
        duplicate,
        task.getTaskState(),
        task.getConsumptionState(),
        task.getOaNo(),
        task.getOaFormItemId(),
        task.getAccountingMonth(),
        task.getDrawingNo());
  }

  public record Receipt(
      String taskId,
      Long localTaskId,
      Long resultId,
      boolean duplicate,
      String taskState,
      String consumptionState,
      String oaNo,
      Long oaFormItemId,
      String accountingMonth,
      String drawingNo) {}
}
