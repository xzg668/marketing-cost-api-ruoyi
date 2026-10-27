package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** U9 无可用 BOM 时，从核算入口接续电子图库，并把结果留在共享 BOM 准备链。 */
@Service
public class ElectronicDrawingCostingFallbackService {
  private static final Logger log =
      LoggerFactory.getLogger(ElectronicDrawingCostingFallbackService.class);

  private final ElectronicDrawingPreparationSource preparation;
  private final QuoteBomContextResolver contextResolver;
  private final ElectronicDrawingWorkflowOrchestrator orchestrator;
  private final DrawingVerificationService verifications;
  private final ElectronicDrawingWorkflowContextPort contexts;

  public ElectronicDrawingCostingFallbackService(
      QuoteBomContextResolver contextResolver,
      ElectronicDrawingWorkflowOrchestrator orchestrator,
      ElectronicDrawingPreparationSource preparation,
      DrawingVerificationService verifications,
      ElectronicDrawingWorkflowContextPort contexts) {
    this.preparation = preparation;
    this.verifications = verifications;
    this.contexts = contexts;
    this.contextResolver = contextResolver;
    this.orchestrator = orchestrator;
  }

  public AttemptResult attempt(OaForm form, OaFormItem item, String periodMonth) {
    return attempt(form, item, periodMonth, false);
  }

  /** 已发布 BOM 仍可能有原材料修订；仅接续已组好的本单来源，不重新取图库。 */
  public AttemptResult resumeComposed(OaForm form, OaFormItem item, String periodMonth) {
    return attempt(form, item, periodMonth, true);
  }

  private AttemptResult attempt(
      OaForm form, OaFormItem item, String periodMonth, boolean composedOnly) {
    if (!composedOnly
        && form != null
        && item != null
        && preparation.prepareSharedDrawing(item.getId(), periodMonth)) {
      return new AttemptResult(true, false, ElectronicDrawingWorkflowStage.COMPOSED, "沿用原产品已提交图库");
    }
    if (form == null || item == null) {
      return AttemptResult.notAttempted();
    }
    try {
      QuoteBomContext context =
          contextResolver.resolveWithExistingCostPeriod(form, item, periodMonth);
      String businessUnit = firstText(item.getBusinessUnitType(), form.getBusinessUnitType());
      if (!StringUtils.hasText(businessUnit)) {
        return AttemptResult.failed("报价产品缺少业务单元，不能读取电子图库");
      }
      String drawingNo =
          preparation.composedDrawingNo(
              item.getId(),
              context.costPeriodMonth(),
              businessUnit,
              context.organization().priceOrgCode());
      if (composedOnly && !StringUtils.hasText(drawingNo)) return AttemptResult.notAttempted();
      if (!StringUtils.hasText(drawingNo)) {
        var work =
            contexts.load(
                item.getId(),
                businessUnit,
                context.organization().priceOrgCode(),
                context.costPeriodMonth());
        var task = verifications.importedForContinuation(work);
        if (task.isEmpty()) task = verifications.start(work, null);
        if (task.isEmpty()) return new AttemptResult(true, true, "U9_BOM_AVAILABLE", "U9 BOM 已可用");
        var saved = task.orElseThrow();
        if ("RECEIVED".equals(saved.getTaskState())) {
          var result =
              orchestrator.process(
                  new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
                      item.getId(),
                      item.getId(),
                      businessUnit,
                      context.organization().priceOrgCode(),
                      saved.getDrawingNo(),
                      null,
                      "财务报价",
                      context.costPeriodMonth()));
          return new AttemptResult(
              true, result.costingCanContinue(), result.stage(), result.message());
        }
        String stage =
            switch (saved.getTaskState()) {
              case "WAIT_REVIEW" -> ElectronicDrawingWorkflowStage.WAIT_REVIEW;
              case "RECEIVED" -> ElectronicDrawingWorkflowStage.RECEIVED;
              case "REQUESTING" -> ElectronicDrawingWorkflowStage.QUERYING;
              default -> ElectronicDrawingWorkflowStage.FAILED;
            };
        String message =
            switch (saved.getTaskState()) {
              case "WAIT_REVIEW" -> "图库有 " + saved.getDifferenceCount() + " 处差异，请打开核对页面编辑并确认";
              case "RECEIVED" -> "电子图库明细已保存，等待物料匹配和 BOM 处理";
              case "REQUESTING" -> "电子图库核验已发起，正在等待结果；请勿重复发起";
              case "SUPERSEDED" -> "报价产品已变化，原取图结果已保留为历史";
              default ->
                  saved.getErrorMessage() == null ? "电子图库核验未完成，请核查任务记录" : saved.getErrorMessage();
            };
        return new AttemptResult(true, false, stage, message);
      }
      ElectronicDrawingWorkflowOrchestrator.WorkflowResult result =
          orchestrator.process(
              new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
                  item.getId(),
                  item.getId(),
                  businessUnit,
                  context.organization().priceOrgCode(),
                  drawingNo,
                  null,
                  "财务报价",
                  context.costPeriodMonth()));
      return new AttemptResult(true, result.costingCanContinue(), result.stage(), result.message());
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      log.warn(
          "electronic drawing costing fallback will retry: oaNo={} itemId={}",
          form.getOaNo(),
          item.getId(),
          exception);
      return new AttemptResult(
          true, false, ElectronicDrawingWorkflowStage.RETRY, exception.getMessage());
    } catch (RuntimeException exception) {
      log.error(
          "electronic drawing costing fallback failed: oaNo={} itemId={} exceptionType={}",
          form.getOaNo(),
          item.getId(),
          exception.getClass().getSimpleName());
      return AttemptResult.failed(exception.getMessage());
    }
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first)
        ? first.trim()
        : StringUtils.hasText(second) ? second.trim() : null;
  }

  public record AttemptResult(
      boolean attempted, boolean costingCanContinue, String stage, String message) {
    static AttemptResult notAttempted() {
      return new AttemptResult(false, false, null, null);
    }

    static AttemptResult failed(String message) {
      return new AttemptResult(true, false, ElectronicDrawingWorkflowStage.FAILED, message);
    }
  }
}
