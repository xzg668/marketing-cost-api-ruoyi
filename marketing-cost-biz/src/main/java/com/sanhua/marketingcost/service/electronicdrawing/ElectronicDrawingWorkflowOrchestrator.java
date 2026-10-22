package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从已接收的图纸结果接续料号匹配和混合 BOM；获取与消费各自保留持久状态。 */
@Service
public class ElectronicDrawingWorkflowOrchestrator {

  private static final Logger log =
      LoggerFactory.getLogger(ElectronicDrawingWorkflowOrchestrator.class);
  private static final Long SYSTEM_USER_ID = null;
  private static final String SYSTEM_PROCESSING = "系统处理中";

  private final ElectronicDrawingWorkflowContextPort contextPort;
  private final QuoteProductBomPreparationService preparationService;
  private final DrawingVerificationService verifications;
  private final DrawingVerificationTaskMapper verificationTasks;
  private final ElectronicDrawingSourceImportService importService;
  private final ElectronicDrawingMaterialResolutionService resolutionService;
  private final ElectronicDrawingHybridBomService hybridBomService;
  private final ElectronicDrawingAutoPublicationService autoPublicationService;

  public ElectronicDrawingWorkflowOrchestrator(
      ElectronicDrawingWorkflowContextPort contextPort,
      QuoteProductBomPreparationService preparationService,
      DrawingVerificationService verifications,
      DrawingVerificationTaskMapper verificationTasks,
      ElectronicDrawingSourceImportService importService,
      ElectronicDrawingMaterialResolutionService resolutionService,
      ElectronicDrawingHybridBomService hybridBomService,
      ElectronicDrawingAutoPublicationService autoPublicationService) {
    this.contextPort = contextPort;
    this.preparationService = preparationService;
    this.verifications = verifications;
    this.verificationTasks = verificationTasks;
    this.importService = importService;
    this.resolutionService = resolutionService;
    this.hybridBomService = hybridBomService;
    this.autoPublicationService = autoPublicationService;
  }

  /** 单品、整单和恢复消费者共用；此方法只消费已存结果，不发起核验 POST。 */
  public WorkflowResult process(WorkflowCommand command) {
    ValidCommand valid = validate(command);
    var context =
        load(
            valid.workflowId(),
            valid.businessUnitType(),
            valid.applicableOrgCode(),
            valid.accountingMonth());
    validateContextBinding(context, valid);
    var task = verifications.current(context).orElse(null);
    if (task != null && !"RECEIVED".equals(task.getTaskState())) return awaiting(context, task);
    try {
      context = ensurePreparation(context, valid);
      if (task != null
          && (context.sourceVersionId() == null
              || !Objects.equals(task.getCurrentResultId(), task.getConsumedResultId()))) {
        try {
          importService.importSource(context, task.getCurrentResultId());
        } catch (RuntimeException failure) {
          String message =
              failure instanceof ElectronicDrawingSourceImportException
                  ? failure.getMessage()
                  : "已接收结果导入失败，请按结果编号核查后重试";
          verificationTasks.failConsumption(
              task.getId(),
              task.getCurrentResultId(),
              message,
              LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
          log.error(
              "drawing consumption failed: localTaskId={} externalTaskId={} resultId={}"
                  + " exceptionType={}",
              task.getId(),
              task.getExternalTaskId(),
              task.getCurrentResultId(),
              failure.getClass().getSimpleName());
          throw failure;
        }
        context =
            load(
                context.workflowId(),
                context.businessUnitType(),
                context.applicableOrgCode(),
                context.accountingMonth());
      }
      if (context.sourceVersionId() == null) {
        return result(context, ElectronicDrawingWorkflowStage.RECEIVED, "尚未取得可导入的图纸明细", false, 0);
      }
      if (autoPublicationService.isPublished(context)) {
        return result(
            context, ElectronicDrawingWorkflowStage.PUBLISHED, "电子图库 BOM 已发布并进入后续核算", true, 0);
      }
      if (ElectronicDrawingWorkflowStage.COMPOSED.equals(context.workflowStage()))
        return publish(context, 0);
      context =
          stage(
              context, ElectronicDrawingWorkflowStage.MATCHING, SYSTEM_USER_ID, SYSTEM_PROCESSING);
      var resolution =
          resolutionService.autoMatch(
              context.workflowId(),
              context.businessUnitType(),
              context.applicableOrgCode(),
              context.accountingMonth());
      context =
          load(
              context.workflowId(),
              context.businessUnitType(),
              context.applicableOrgCode(),
              context.accountingMonth());
      if (!resolution.complete()) {
        context =
            stage(
                context,
                ElectronicDrawingWorkflowStage.MAPPING_PENDING,
                valid.financeUserId(),
                valid.financeUserName());
        record(context, "E_DRAWING_MAPPING_PENDING", "已接收图纸明细，待报价员确认未唯一匹配的 U9 料号");
        return result(
            context,
            ElectronicDrawingWorkflowStage.MAPPING_PENDING,
            "图纸明细已接收，仍有 "
                + (resolution.unmatchedCount() + resolution.ambiguousCount())
                + " 个物料需要选择 U9 料号",
            false,
            0);
      }
      return compose(context);
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      throw exception;
    } catch (ElectronicDrawingSourceImportException
        | ElectronicDrawingMaterialResolutionException
        | ElectronicDrawingHybridBomException exception) {
      if (isOptimisticConflict(exception)) {
        throw new ElectronicDrawingWorkflowRetryException("电子图库处理版本已变化，请读取最新结果后继续", exception);
      }
      return validationFailed(context, valid, exception);
    } catch (RuntimeException failure) {
      log.error(
          "drawing processing failed: workflowId={} exceptionType={}",
          context.workflowId(),
          failure.getClass().getSimpleName());
      throw new ElectronicDrawingWorkflowRetryException("已保存的图纸结果处理暂未完成，可继续处理，无需重新核验", failure);
    }
  }

  /** 技术复查同样先核实 U9；核验在草稿写事务之前完成，已有任务不重复 POST。 */
  public DrawingVerificationTask acquire(WorkflowCommand command) {
    ValidCommand valid = validate(command);
    var context =
        load(
            valid.workflowId(),
            valid.businessUnitType(),
            valid.applicableOrgCode(),
            valid.accountingMonth());
    validateContextBinding(context, valid);
    // 技术检查采用当前已存结果。重新获取由明确携带旧任务编号的报价操作发起，
    // 不能因为 worker 已消费完成就丢开人工确认结果，再次进入待确认。
    var acquired = verifications.start(context, valid.drawingNo());
    return acquired.orElseThrow(
        () ->
            new ElectronicDrawingSourceImportException(
                ElectronicDrawingSourceImportException.BINDING_INVALID,
                "U9 BOM 已可用，请重新检查本产品的 BOM 来源"));
  }

  /** 技术复查只导入已接收结果并匹配；审批及核算门槛继续由各自业务校验。 */
  @org.springframework.transaction.annotation.Transactional
  public ElectronicDrawingWorkContext refreshSource(
      WorkflowCommand command, DrawingVerificationTask acquired) {
    ValidCommand valid = validate(command);
    var context =
        load(
            valid.workflowId(),
            valid.businessUnitType(),
            valid.applicableOrgCode(),
            valid.accountingMonth());
    validateContextBinding(context, valid);
    if (acquired == null
        || !"RECEIVED".equals(acquired.getTaskState())
        || acquired.getCurrentResultId() == null) {
      throw new ElectronicDrawingSourceImportException(
          ElectronicDrawingSourceImportException.SOURCE_INVALID, "图纸明细尚未确认，请在报价系统打开核对页面完成确认");
    }
    context = ensurePreparation(context, valid);
    importService.importSource(context, acquired.getCurrentResultId());
    context =
        load(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    if (context.published()) return context;
    var resolution =
        resolutionService.autoMatch(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    context =
        load(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    return stage(
        context,
        resolution.complete()
            ? ElectronicDrawingWorkflowStage.MATCHED
            : ElectronicDrawingWorkflowStage.MAPPING_PENDING,
        valid.financeUserId(),
        valid.financeUserName());
  }

  private WorkflowResult awaiting(
      ElectronicDrawingWorkContext context, DrawingVerificationTask task) {
    String stage =
        switch (task.getTaskState()) {
          case "WAIT_REVIEW" -> ElectronicDrawingWorkflowStage.WAIT_REVIEW;
          case "REQUESTING" -> ElectronicDrawingWorkflowStage.QUERYING;
          default -> ElectronicDrawingWorkflowStage.FAILED;
        };
    String message =
        "WAIT_REVIEW".equals(task.getTaskState())
            ? "图纸明细存在差异，请打开核对页面编辑并确认"
            : "图纸核验尚未完成，请查看已保存的任务状态";
    return result(context, stage, message, false, 0);
  }

  /** 财务分批保存选择后，仅在全部物料已就绪时继续合成，不重复调用电子图库接口。 */
  public WorkflowResult resumeAfterMaterialSelection(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth) {
    ElectronicDrawingWorkContext context =
        load(workflowId, businessUnitType, applicableOrgCode, accountingMonth);
    try {
      ElectronicDrawingMaterialResolutionResponse resolution =
          resolutionService.autoMatch(
              context.workflowId(),
              context.businessUnitType(),
              context.applicableOrgCode(),
              context.accountingMonth());
      context =
          load(
              context.workflowId(),
              context.businessUnitType(),
              context.applicableOrgCode(),
              context.accountingMonth());
      if (!resolution.complete()) {
        return result(
            context,
            ElectronicDrawingWorkflowStage.MAPPING_PENDING,
            "已保存本次选择，仍有 " + (resolution.unmatchedCount() + resolution.ambiguousCount()) + " 个物料待处理",
            false,
            0);
      }
      return compose(context);
    } catch (ElectronicDrawingMaterialResolutionException
        | ElectronicDrawingHybridBomException exception) {
      if (isOptimisticConflict(exception)) {
        throw new ElectronicDrawingWorkflowRetryException("电子图库任务状态已变化，请刷新页面后重试", exception);
      }
      return validationFailed(
          context,
          new ValidCommand(
              context.workflowId(),
              null,
              context.businessUnitType(),
              context.applicableOrgCode(),
              firstText(context.quoteProductCode(), "UNKNOWN"),
              null,
              "财务报价",
              context.accountingMonth()),
          exception);
    }
  }

  public WorkflowResult resumeAfterMaterialSelection(Long workflowId, String accountingMonth) {
    ElectronicDrawingWorkContext context =
        contextPort.loadForCurrentBusinessUnit(workflowId, accountingMonth);
    return resumeAfterMaterialSelection(
        workflowId,
        context.businessUnitType(),
        context.applicableOrgCode(),
        context.accountingMonth());
  }

  private WorkflowResult compose(ElectronicDrawingWorkContext context) {
    context =
        stage(context, ElectronicDrawingWorkflowStage.COMPOSING, SYSTEM_USER_ID, SYSTEM_PROCESSING);
    ElectronicDrawingHybridBomService.CompositionResult composition =
        hybridBomService.compose(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    context =
        load(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    context =
        stage(context, ElectronicDrawingWorkflowStage.COMPOSED, SYSTEM_USER_ID, SYSTEM_PROCESSING);
    record(
        context,
        "E_DRAWING_COMPOSED",
        "电子图库与 U9 子 BOM 已合成，形成 " + composition.quotationLeafCount() + " 条报价物料");
    return publish(context, composition.quotationLeafCount());
  }

  private WorkflowResult publish(ElectronicDrawingWorkContext context, int quotationLeafCount) {
    String blocked = autoPublicationService.blockingReason(context);
    if (blocked != null)
      return result(
          context,
          ElectronicDrawingWorkflowStage.COMPOSED,
          "BOM 已组好，仍需完成补录审批及报价确认：" + blocked,
          false,
          quotationLeafCount);
    ElectronicDrawingAutoPublicationService.PublicationResult published =
        autoPublicationService.publish(
            context.workflowId(),
            context.businessUnitType(),
            context.applicableOrgCode(),
            context.accountingMonth());
    ElectronicDrawingWorkContext current = published.context();
    record(current, "E_DRAWING_PUBLISHED", "电子图库 BOM 已自动发布，继续价格检查和核算");
    return result(
        current,
        ElectronicDrawingWorkflowStage.PUBLISHED,
        quotationLeafCount > 0
            ? "电子图库 BOM 已准备完成，共 " + quotationLeafCount + " 条报价物料，正在继续核算"
            : "电子图库 BOM 已自动发布，正在继续核算",
        true,
        quotationLeafCount);
  }

  private ElectronicDrawingWorkContext ensurePreparation(
      ElectronicDrawingWorkContext context, ValidCommand command) {
    if (context.preparationId() != null) return context;
    QuoteProductBomPreparationPreview preparation =
        preparationService.prepareByOaFormItem(
            command.oaFormItemId(),
            CostPricingPeriodUtils.currentPricingDate(),
            context.accountingMonth());
    if (preparation == null || preparation.preparationRecordId() == null) {
      throw new IllegalStateException("电子图库处理所需的 BOM 准备记录创建失败");
    }
    if (!Objects.equals(context.accountingMonth(), preparation.periodMonth())
        || !same(context.quoteProductCode(), preparation.quoteProductCode())) {
      throw new IllegalStateException("电子图库 BOM 准备记录与当前产品或核算月份不一致");
    }
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    return contextPort.attachPreparation(
        context,
        preparation.preparationRecordId(),
        ElectronicDrawingWorkflowStage.QUERYING,
        SYSTEM_USER_ID,
        SYSTEM_PROCESSING,
        now);
  }

  private WorkflowResult validationFailed(
      ElectronicDrawingWorkContext context, ValidCommand command, RuntimeException exception) {
    context =
        safeStage(
            context,
            ElectronicDrawingWorkflowStage.VALIDATION_FAILED,
            SYSTEM_USER_ID,
            SYSTEM_PROCESSING);
    log.error(
        "electronic drawing validation blocked: workflowId={} drawingNo={} exceptionType={}",
        context.workflowId(),
        command.drawingNo(),
        exception.getClass().getSimpleName());
    String message =
        exception instanceof ElectronicDrawingSourceImportException
            ? exception.getMessage()
            : exception instanceof ElectronicDrawingHybridBomException hybrid
                    && ElectronicDrawingHybridBomException.BOM_GAP.equals(hybrid.code())
                ? hybrid.getMessage()
                : "电子图库资料暂无法完成校验，请稍后重新检查";
    record(context, "E_DRAWING_VALIDATION_FAILED", message);
    return result(context, ElectronicDrawingWorkflowStage.VALIDATION_FAILED, message, false, 0);
  }

  private ElectronicDrawingWorkContext safeStage(
      ElectronicDrawingWorkContext context,
      String nextStage,
      Long assigneeUserId,
      String assigneeName) {
    try {
      ElectronicDrawingWorkContext current =
          load(
              context.workflowId(),
              context.businessUnitType(),
              context.applicableOrgCode(),
              context.accountingMonth());
      return stage(current, nextStage, assigneeUserId, assigneeName);
    } catch (RuntimeException stageFailure) {
      log.error(
          "electronic drawing stage persistence failed: opsAlert=true taskId={} stage={}",
          context.workflowId(),
          nextStage,
          stageFailure);
      return context;
    }
  }

  private ElectronicDrawingWorkContext stage(
      ElectronicDrawingWorkContext context,
      String nextStage,
      Long assigneeUserId,
      String assigneeName) {
    if (Objects.equals(nextStage, context.workflowStage())
        && Objects.equals(assigneeUserId, context.assigneeUserId())
        && Objects.equals(assigneeName, context.assigneeName())) {
      return context;
    }
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    return contextPort.updateStage(context, nextStage, assigneeUserId, assigneeName, now);
  }

  private ElectronicDrawingWorkContext load(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth) {
    return contextPort.load(workflowId, businessUnitType, applicableOrgCode, accountingMonth);
  }

  private void validateContextBinding(ElectronicDrawingWorkContext context, ValidCommand command) {
    if (!context.active()
        || !context.bomRequired()
        || !Objects.equals(context.businessUnitType(), command.businessUnitType())
        || !Objects.equals(context.oaFormItemId(), command.oaFormItemId())
        || !Objects.equals(context.accountingMonth(), command.accountingMonth())
        || !Objects.equals(context.applicableOrgCode(), command.applicableOrgCode())) {
      throw new IllegalArgumentException("当前报价产品不允许进入电子图库处理");
    }
  }

  private void record(ElectronicDrawingWorkContext context, String eventType, String description) {
    try {
      contextPort.record(context, eventType, description);
    } catch (RuntimeException exception) {
      log.error(
          "electronic drawing audit failed: opsAlert=true taskId={} eventType={}",
          context.workflowId(),
          eventType,
          exception);
    }
  }

  private static boolean isOptimisticConflict(RuntimeException exception) {
    if (exception instanceof ElectronicDrawingSourceImportException source) {
      return ElectronicDrawingSourceImportException.TASK_VERSION_CONFLICT.equals(source.getCode());
    }
    if (exception instanceof ElectronicDrawingMaterialResolutionException resolution) {
      return ElectronicDrawingMaterialResolutionException.TASK_VERSION_CONFLICT.equals(
          resolution.code());
    }
    if (exception instanceof ElectronicDrawingHybridBomException hybrid) {
      return ElectronicDrawingHybridBomException.TASK_VERSION_CONFLICT.equals(hybrid.code());
    }
    return false;
  }

  private static WorkflowResult result(
      ElectronicDrawingWorkContext context,
      String stage,
      String message,
      boolean complete,
      int quotationLeafCount) {
    return new WorkflowResult(
        context.workflowId(),
        context.revision(),
        context.sourceVersionId(),
        stage,
        message,
        complete,
        quotationLeafCount);
  }

  private static boolean same(String left, String right) {
    return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
  }

  private static ValidCommand validate(WorkflowCommand command) {
    if (command == null
        || command.workflowId() == null
        || command.workflowId() <= 0
        || command.oaFormItemId() == null
        || command.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("电子图库编排缺少产品任务或报价产品行");
    }
    String businessUnit = required(command.businessUnitType(), "业务单元");
    String org = required(command.applicableOrgCode(), "适用组织");
    String drawing = required(command.drawingNo(), "产品图号");
    String financeName = required(command.financeUserName(), "财务报价员");
    String month =
        java.time.YearMonth.parse(required(command.accountingMonth(), "核算月份")).toString();
    return new ValidCommand(
        command.workflowId(),
        command.oaFormItemId(),
        businessUnit,
        org,
        drawing,
        command.financeUserId(),
        financeName,
        month);
  }

  private static String required(String value, String label) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + "不能为空");
    return value.trim();
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  public record WorkflowCommand(
      Long workflowId,
      Long oaFormItemId,
      String businessUnitType,
      String applicableOrgCode,
      String drawingNo,
      Long financeUserId,
      String financeUserName,
      String accountingMonth) {}

  public record WorkflowResult(
      Long workflowId,
      Integer revision,
      Long sourceVersionId,
      String stage,
      String message,
      boolean complete,
      int quotationLeafCount) {

    public boolean costingCanContinue() {
      return ElectronicDrawingWorkflowStage.PUBLISHED.equals(stage);
    }
  }

  private record ValidCommand(
      Long workflowId,
      Long oaFormItemId,
      String businessUnitType,
      String applicableOrgCode,
      String drawingNo,
      Long financeUserId,
      String financeUserName,
      String accountingMonth) {}
}
