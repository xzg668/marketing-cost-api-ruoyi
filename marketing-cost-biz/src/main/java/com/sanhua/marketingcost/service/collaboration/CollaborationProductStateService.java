package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 产品任务状态、责任人和下一步的一致性事务入口。 */
@Service
public class CollaborationProductStateService {

  private static final Assignment NONE = new Assignment(null, null);
  private static final Assignment SYSTEM = new Assignment(null, "系统");
  private static final Assignment COSTING_ROLE = new Assignment(null, "核算角色");

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationAuthorization authorization;
  private final CollaborationNextActionCalculator nextActionCalculator;
  private final CollaborationResponsibilityRules responsibilityRules;
  private final CollaborationTransitionEventFactory eventFactory;
  private final CollaborationEventService eventService;
  private final CollaborationLinkedQuoteRecheckService linkedQuoteRecheckService;

  public CollaborationProductStateService(
      QuoteCollaborationTaskRepository repository,
      CollaborationAuthorization authorization,
      CollaborationNextActionCalculator nextActionCalculator,
      CollaborationResponsibilityRules responsibilityRules,
      CollaborationTransitionEventFactory eventFactory,
      CollaborationEventService eventService,
      CollaborationLinkedQuoteRecheckService linkedQuoteRecheckService) {
    this.repository = repository;
    this.authorization = authorization;
    this.nextActionCalculator = nextActionCalculator;
    this.responsibilityRules = responsibilityRules;
    this.eventFactory = eventFactory;
    this.eventService = eventService;
    this.linkedQuoteRecheckService = linkedQuoteRecheckService;
  }

  @Transactional
  public ProductTransitionResult transition(
      Long productTaskId,
      Integer expectedVersion,
      CollaborationScope scope,
      ProductAction action,
      CollaborationPrincipal principal) {
    QuoteCollaborationProductTask task = repository.findProductTaskById(productTaskId, scope)
        .orElseThrow(() -> notFound("产品任务不存在或不在当前业务范围"));
    requireVersion(task.getTaskVersion(), expectedVersion);
    ProductTaskStatus source = parse(task.getTaskStatus());
    ProductTaskStatus target = CollaborationStateMachines.transitionProduct(source, action);
    authorization.requireProductAction(task, action, principal);
    requireUniqueStartAction(task, source, action, principal);
    QuoteCollaborationTask master = repository.findTaskById(
        task.getOriginCollaborationId(), task.getBusinessUnitType())
        .orElseThrow(() -> notFound("产品任务所属主任务不存在"));
    Assignment assignment = assignment(target, task, master, principal);
    try {
      QuoteCollaborationProductTask updated = repository.transitionProductTaskStatus(
          task.getId(), expectedVersion, source.code(), target.code(),
          assignment.userId(), assignment.userName(), scope, principal.actor());
      responsibilityRules.requireConsistent(updated, master);
      CollaborationNextAction nextAction = nextActionCalculator.calculate(updated, principal);
      eventFactory.productTransition(updated, master, source, action, nextAction,
              action == ProductAction.FAIL_TECH_VALIDATION
                  ? repository.findGaps(updated.getId(), scope) : java.util.List.of())
          .ifPresent(eventService::append);
      if (target == ProductTaskStatus.READY_FOR_COSTING) {
        linkedQuoteRecheckService.startLinkedQuoteRechecks(
            updated.getId(), scope, principal);
      } else if (target == ProductTaskStatus.CANCELLED) {
        linkedQuoteRecheckService.cancelActiveQuoteLinks(
            updated.getId(), scope, principal);
      }
      return new ProductTransitionResult(updated, nextAction);
    } catch (CollaborationOptimisticLockException exception) {
      throw versionConflict();
    }
  }

  private void requireUniqueStartAction(
      QuoteCollaborationProductTask task,
      ProductTaskStatus source,
      ProductAction action,
      CollaborationPrincipal principal) {
    if (source != ProductTaskStatus.WAIT_TECH
        || (action != ProductAction.START_BOM
            && action != ProductAction.START_PACKAGE
            && action != ProductAction.START_PRICE)) {
      return;
    }
    ProductAction expected = switch (nextActionCalculator.calculate(task, principal)) {
      case SUPPLEMENT_BOM -> ProductAction.START_BOM;
      case SUPPLEMENT_PACKAGE -> ProductAction.START_PACKAGE;
      case SUPPLEMENT_PRICE -> ProductAction.START_PRICE;
      default -> null;
    };
    if (expected == null || action != expected) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "当前任务唯一允许的开始动作是" + expected);
    }
  }

  private static Assignment assignment(
      ProductTaskStatus target,
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      CollaborationPrincipal principal) {
    return switch (target) {
      case WAIT_TECH, BOM_IN_PROGRESS, PACKAGE_IN_PROGRESS, PRICE_IN_PROGRESS,
          TECH_VALIDATION_FAILED, RETURNED_TO_TECH -> new Assignment(
              task.getOriginalTechnicianUserId(), task.getOriginalTechnicianName());
      case TECH_SUBMITTED, COMPLETED, CANCELLED -> NONE;
      case WAIT_FINANCE -> new Assignment(
          master.getFinanceReviewerUserId(), master.getFinanceReviewerName());
      case APPROVED_PUBLISHING, PUBLISH_OR_REPRICE_FAILED -> SYSTEM;
      case READY_FOR_COSTING -> COSTING_ROLE;
      case COSTING -> new Assignment(principal.userId(), principal.userName());
    };
  }

  private static ProductTaskStatus parse(String status) {
    try {
      return ProductTaskStatus.valueOf(status == null ? "" : status);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知产品任务状态：" + status);
    }
  }

  private static void requireVersion(Integer current, Integer expected) {
    if (expected == null || expected <= 0 || !expected.equals(current)) {
      throw versionConflict();
    }
  }

  private static CollaborationDomainException notFound(String message) {
    return new CollaborationDomainException(CollaborationDomainErrorCode.TASK_NOT_FOUND, message);
  }

  private static CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
        "任务版本已变化，请刷新页面后重试");
  }

  private record Assignment(Long userId, String userName) {}

  public record ProductTransitionResult(
      QuoteCollaborationProductTask task,
      CollaborationNextAction nextAction) {}
}
