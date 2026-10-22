package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.costing.ProductCostingContext;
import com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 从已保存结果续接；自动扫描和人工重试共用，不重新核验 PDF。 */
@Service
public class DrawingResultConsumptionService {
  private static final Logger log = LoggerFactory.getLogger(DrawingResultConsumptionService.class);
  private final DrawingVerificationTaskMapper tasks;
  private final OaFormItemMapper items;
  private final OaFormMapper forms;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final DrawingVerificationService verifications;
  private final ElectronicDrawingWorkflowOrchestrator workflow;
  private final ProductCostingExecutionGuard guard;
  private final OaWorkflowAccessPolicy access;
  private final TransactionTemplate transaction;

  public DrawingResultConsumptionService(
      DrawingVerificationTaskMapper tasks,
      OaFormItemMapper items,
      OaFormMapper forms,
      ElectronicDrawingWorkflowContextPort contexts,
      DrawingVerificationService verifications,
      ElectronicDrawingWorkflowOrchestrator workflow,
      ProductCostingExecutionGuard guard,
      OaWorkflowAccessPolicy access,
      PlatformTransactionManager transactionManager) {
    this.tasks = tasks;
    this.items = items;
    this.forms = forms;
    this.contexts = contexts;
    this.verifications = verifications;
    this.workflow = workflow;
    this.guard = guard;
    this.access = access;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /** FAILED 也可显式重试；后台只扫描 PENDING，避免永久资料缺口反复刷日志。 */
  public Outcome consume(Long taskId) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("图纸结果续接必须在接收事务提交后执行");
    }
    var task = tasks.selectById(taskId);
    if (!eligible(task))
      return new Outcome(taskId, task == null ? null : task.getCurrentResultId(), "SKIPPED", null);
    var item = items.selectById(task.getOaFormItemId());
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null || !Objects.equals(form.getOaNo(), task.getOaNo())) {
      return fail(task, "报价产品归属已变化，无法续接已保存的图纸结果", null);
    }
    var scope =
        new ProductCostingContext(
            form,
            item,
            QuoteProductIdentityUtils.resolveCostingCode(item),
            task.getAccountingMonth(),
            "图纸结果续接",
            null);
    return guard.execute(scope, () -> consumeLocked(taskId));
  }

  private Outcome consumeLocked(Long taskId) {
    var task = tasks.selectById(taskId);
    if (!eligible(task))
      return new Outcome(taskId, task == null ? null : task.getCurrentResultId(), "SKIPPED", null);
    try {
      var context =
          contexts.load(
              task.getOaFormItemId(),
              task.getBusinessUnitType(),
              task.getPriceOrgCode(),
              task.getAccountingMonth());
      boolean current =
          transaction.execute(
              status -> {
                items.selectForCostingWrite(task.getOaFormItemId());
                var locked = tasks.selectForUpdate(taskId);
                if (!eligible(locked)) return false;
                if (verifications.inputsStillMatch(context, locked)) return true;
                tasks.supersede(locked.getId(), locked.getRowVersion(), now());
                return false;
              });
      if (!current) return new Outcome(taskId, task.getCurrentResultId(), "SUPERSEDED", null);
      // 确认可以作为历史接收；续接仍遵守报价单当前的 OA 办理范围。
      try {
        access.requireCosting(context.oaFormId());
      } catch (IllegalArgumentException blocked) {
        return fail(task, blocked.getMessage(), blocked);
      }
      if (verifications.hasU9Bom(context, items.selectById(task.getOaFormItemId()))) {
        tasks.deferToU9(taskId, task.getCurrentResultId(), now());
        log.info(
            "drawing consumption uses U9: localTaskId={} externalTaskId={} resultId={}",
            taskId,
            task.getExternalTaskId(),
            task.getCurrentResultId());
        return new Outcome(taskId, task.getCurrentResultId(), "U9_BOM_AVAILABLE", null);
      }
      var result =
          workflow.process(
              new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
                  context.workflowId(),
                  context.oaFormItemId(),
                  context.businessUnitType(),
                  context.applicableOrgCode(),
                  task.getDrawingNo(),
                  null,
                  "财务报价",
                  context.accountingMonth()));
      var saved = tasks.selectById(taskId);
      if ("FAILED".equals(saved.getConsumptionState())
          && Objects.equals(saved.getConsumedResultId(), saved.getCurrentResultId())) {
        tasks.completeConsumption(taskId, saved.getCurrentResultId(), now());
        saved = tasks.selectById(taskId);
      }
      log.info(
          "drawing consumption continued: localTaskId={} externalTaskId={} resultId={}"
              + " consumptionState={} workflowStage={}",
          taskId,
          saved.getExternalTaskId(),
          saved.getCurrentResultId(),
          saved.getConsumptionState(),
          result.stage());
      return new Outcome(
          taskId, saved.getCurrentResultId(), saved.getConsumptionState(), result.stage());
    } catch (RuntimeException failure) {
      String message =
          failure instanceof ElectronicDrawingSourceImportException
              ? failure.getMessage()
              : "已保存的图纸结果续接失败，请按结果编号排查后重试";
      return fail(task, message, failure);
    }
  }

  private Outcome fail(DrawingVerificationTask task, String message, RuntimeException failure) {
    var current = tasks.selectById(task.getId());
    if (current != null
        && !("FAILED".equals(current.getConsumptionState())
            && Objects.equals(current.getCurrentResultId(), task.getCurrentResultId()))) {
      tasks.failConsumption(task.getId(), task.getCurrentResultId(), message, now());
    }
    log.error(
        "drawing consumption interrupted: localTaskId={} externalTaskId={} resultId={}"
            + " exceptionType={}",
        task.getId(),
        task.getExternalTaskId(),
        task.getCurrentResultId(),
        failure == null ? "BINDING_CHANGED" : failure.getClass().getSimpleName());
    return new Outcome(task.getId(), task.getCurrentResultId(), "FAILED", null);
  }

  private boolean eligible(DrawingVerificationTask task) {
    return task != null
        && task.getActiveScopeKey() != null
        && "RECEIVED".equals(task.getTaskState())
        && task.getCurrentResultId() != null
        && ("PENDING".equals(task.getConsumptionState())
            || "FAILED".equals(task.getConsumptionState()));
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }

  public record Outcome(
      Long localTaskId, Long resultId, String consumptionState, String workflowStage) {}
}
