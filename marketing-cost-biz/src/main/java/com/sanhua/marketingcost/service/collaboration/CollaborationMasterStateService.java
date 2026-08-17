package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.MasterStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 主任务聚合状态的唯一事务入口。 */
@Service
public class CollaborationMasterStateService {

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationAuthorization authorization;
  private final CollaborationTransitionEventFactory eventFactory;
  private final CollaborationEventService eventService;

  public CollaborationMasterStateService(
      QuoteCollaborationTaskRepository repository,
      CollaborationAuthorization authorization,
      CollaborationTransitionEventFactory eventFactory,
      CollaborationEventService eventService) {
    this.repository = repository;
    this.authorization = authorization;
    this.eventFactory = eventFactory;
    this.eventService = eventService;
  }

  @Transactional
  public QuoteCollaborationTask transition(
      Long taskId,
      Integer expectedVersion,
      String businessUnitType,
      MasterAction action,
      CollaborationPrincipal principal) {
    QuoteCollaborationTask task = repository.findTaskById(taskId, businessUnitType)
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "协作主任务不存在或不在当前业务范围"));
    if (expectedVersion == null || expectedVersion <= 0
        || !expectedVersion.equals(task.getTaskVersion())) {
      throw versionConflict();
    }
    MasterStatus source = parse(task.getMasterStatus());
    MasterStatus target = CollaborationStateMachines.transitionMaster(source, action);
    authorization.requireMasterAction(task, action, principal);
    try {
      QuoteCollaborationTask updated = repository.transitionTaskStatus(
          task.getId(), expectedVersion, source.code(),
          target.code(), businessUnitType, principal.actor());
      eventFactory.masterTransition(updated, source, action).ifPresent(eventService::append);
      return updated;
    } catch (CollaborationOptimisticLockException exception) {
      throw versionConflict();
    }
  }

  private static MasterStatus parse(String status) {
    try {
      return MasterStatus.valueOf(status == null ? "" : status);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知协作主任务状态：" + status);
    }
  }

  private static CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
        "任务版本已变化，请刷新页面后重试");
  }
}
