package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 管理员显式确认的本地提交。沿用资料校验、冻结、发布；不创建 OA 待办或伪造 OA 回执。 */
@Service
public class TechnicalDataAdminDirectSave {
  public static final String SOURCE = "QUOTE_ADMIN_DIRECT";
  public record Request(Integer expectedVersion) {}
  public record Result(long taskId, Long effectiveVersionId, TechnicalDataTaskValidationResponse validation) {}
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository taskRepository;
  private final QuoteTechTaskMapper tasks;
  private final QuoteTechModuleMapper modules;
  private final TechnicalDataSubmissionValidationService validation;
  private final TechnicalDataParticipantVersions versions;
  private final TechnicalDataPricePublication prices;
  private final TechnicalDataAuditLogService audit;
  private final OaWorkflowAccessPolicy access;
  private final TechnicalDataSharedModuleQuery shared;
  private final TechnicalDataRequirementRefreshService requirements;

  public TechnicalDataAdminDirectSave(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository taskRepository, QuoteTechTaskMapper tasks, QuoteTechModuleMapper modules,
      TechnicalDataSubmissionValidationService validation, TechnicalDataParticipantVersions versions,
      TechnicalDataPricePublication prices, TechnicalDataAuditLogService audit, OaWorkflowAccessPolicy access,
      TechnicalDataSharedModuleQuery shared, TechnicalDataRequirementRefreshService requirements) {
    this.repository=repository; this.taskRepository=taskRepository; this.tasks=tasks; this.modules=modules;
    this.validation=validation; this.versions=versions; this.prices=prices; this.audit=audit; this.access=access;
    this.shared = shared; this.requirements = requirements;
  }

  public static boolean isPublished(QuoteTechTask task) {
    return task != null && SOURCE.equals(task.getSourceSystem())
        && Integer.valueOf(1).equals(task.getActiveFlag()) && "SUBMITTED".equals(task.getTaskStatus())
        && task.getAssigneeUserId() != null && task.getAssigneeUserId() > 0
        && task.getOaFlowId() == null && task.getOaDispatchMessageId() == null
        && (task.getOaAssignmentVersion() == null || task.getOaAssignmentVersion() == 0)
        && task.getExternalTaskId() == null && task.getExternalTaskStatus() == null;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Result save(long taskId, Request request, TechnicalDataActor actor) {
    if (actor == null || !actor.admin() || actor.oaSession() || actor.userId() == null)
      throw forbidden("只有管理员可以直接保存未分派产品");
    if (request == null || request.expectedVersion() == null)
      throw new IllegalArgumentException("请提供当前产品版本");
    var task = repository.lockTask(taskId).orElseThrow(() -> new IllegalArgumentException("补录任务不存在"));
    access.requireCosting(task.getOaFormId());
    var list = taskRepository.findProducts(taskId);
    if (list.size() != 1) throw new IllegalArgumentException("直接保存必须对应一个活动产品");
    var product = repository.lockProduct(list.getFirst().getId()).orElseThrow();
    if (isPublished(task) && product.getEffectiveVersionId() != null)
      return new Result(taskId, product.getEffectiveVersionId(), null);
    if (!actor.canFillUnassigned(task)) throw forbidden("产品已分派，管理员不能直接保存");
    if (!Objects.equals(product.getRowVersion(), request.expectedVersion()))
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "产品资料已变化，请刷新后重试");
    requirements.refresh(task, shared.currentRequirements(product, repository.lockModules(product.getId())));
    var checked = validation.validate(taskId, actor.userId(), actor);
    if (!checked.valid()) return new Result(taskId, null, checked);
    var frozen = versions.freezeAdmin(product, request.expectedVersion(), actor);
    product = repository.lockProduct(product.getId()).orElseThrow();
    var submitted = versions.transition(frozen, "SUBMITTED", actor.userId());
    var required = repository.lockModules(product.getId()).stream()
        .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())).toList();
    for (var module : required) {
      module.setModuleStatus("SUBMITTED");
      module.setAssigneeUserId(actor.userId());
      module.setAssigneeName(actor.name());
      if (modules.updateById(module) != 1) throw new IllegalStateException("模块保存失败");
    }
    task.setSourceSystem(SOURCE); task.setTaskStatus("SUBMITTED");
    task.setAssigneeUserId(actor.userId()); task.setAssigneeName(actor.name());
    task.setTaskVersion(task.getTaskVersion() + 1); task.setUpdatedBy(actor.userId());
    task.setSubmittedAt(LocalDateTime.now(com.sanhua.marketingcost.util.CostPricingPeriodUtils.BUSINESS_ZONE));
    if (tasks.updateById(task) != 1) throw new IllegalStateException("任务保存失败");
    if (required.stream().anyMatch(module -> "PRICE".equals(module.getModuleType()))) prices.publish(task, product, submitted);
    var effective = versions.activate(product, actor.userId());
    if (effective == null) throw new IllegalStateException("资料依赖尚未全部通过，不能用于报价");
    audit.record(task, product, effective.getId(), "ADMIN_DIRECT_SAVE", "UNASSIGNED", "SUBMITTED",
        "管理员确认保存，资料直接生效用于报价，无 OA 审批", actor,
        "admin-direct:" + effective.getId(), "TD-ADMIN-DIRECT:" + effective.getId());
    return new Result(taskId, effective.getId(), checked);
  }

  private TechnicalDataTaskException forbidden(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message);
  }
}
