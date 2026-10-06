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

  public ElectronicDrawingCostingFallbackService(
      QuoteBomContextResolver contextResolver,
      ElectronicDrawingWorkflowOrchestrator orchestrator, ElectronicDrawingPreparationSource preparation) {
    this.preparation = preparation;
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

  private AttemptResult attempt(OaForm form, OaFormItem item, String periodMonth,
      boolean composedOnly) {
    if (!composedOnly && form != null && item != null && preparation.prepareSharedDrawing(item.getId(), periodMonth)) {
      return new AttemptResult(true, false, ElectronicDrawingWorkflowStage.COMPOSED, "沿用原产品已提交图库");
    }
    if (form == null || item == null) {
      return AttemptResult.notAttempted();
    }
    try {
      QuoteBomContext context = contextResolver.resolveWithExistingCostPeriod(
          form, item, periodMonth);
      String businessUnit = firstText(item.getBusinessUnitType(), form.getBusinessUnitType());
      if (!StringUtils.hasText(businessUnit)) {
        return AttemptResult.failed("报价产品缺少业务单元，不能读取电子图库");
      }
      String drawingNo = preparation.composedDrawingNo(
          item.getId(), context.costPeriodMonth(), businessUnit,
          context.organization().priceOrgCode());
      if (composedOnly && !StringUtils.hasText(drawingNo)) return AttemptResult.notAttempted();
      drawingNo = firstText(drawingNo, item.getCustomerDrawing());
      if (!StringUtils.hasText(drawingNo)) return AttemptResult.notAttempted();
      ElectronicDrawingWorkflowOrchestrator.WorkflowResult result = orchestrator.process(
          new ElectronicDrawingWorkflowOrchestrator.WorkflowCommand(
              item.getId(), item.getId(), businessUnit,
              context.organization().priceOrgCode(), drawingNo,
              null, "财务报价", context.costPeriodMonth()));
      return new AttemptResult(true, result.costingCanContinue(), result.stage(), result.message());
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      log.warn("electronic drawing costing fallback will retry: oaNo={} itemId={}",
          form.getOaNo(), item.getId(), exception);
      return AttemptResult.failed(exception.getMessage());
    } catch (RuntimeException exception) {
      log.error("electronic drawing costing fallback failed: oaNo={} itemId={}",
          form.getOaNo(), item.getId(), exception);
      return AttemptResult.failed(exception.getMessage());
    }
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first.trim()
        : StringUtils.hasText(second) ? second.trim() : null;
  }

  public record AttemptResult(boolean attempted, boolean costingCanContinue, String stage,
                              String message) {
    static AttemptResult notAttempted() {
      return new AttemptResult(false, false, null, null);
    }

    static AttemptResult failed(String message) {
      return new AttemptResult(true, false, ElectronicDrawingWorkflowStage.RETRY, message);
    }
  }
}
