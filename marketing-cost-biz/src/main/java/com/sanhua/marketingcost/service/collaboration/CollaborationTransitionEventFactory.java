package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationEventType;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.MasterStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 把领域动作映射成OA可理解的最小状态摘要，不携带BOM树或价格明细。 */
@Component
public class CollaborationTransitionEventFactory {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  public CollaborationEventCommand taskCreated(
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      CollaborationNextAction nextAction) {
    return productLifecycleEvent(
        task, master, OaCollaborationEventType.TECH_TASK_CREATED, nextAction, null);
  }

  public CollaborationEventCommand taskLinked(
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      Long oaFormItemId) {
    return productLifecycleEvent(
        task, master, OaCollaborationEventType.TECH_TASK_LINKED,
        CollaborationNextAction.NONE, oaFormItemId);
  }

  public CollaborationEventCommand approvedResultReused(
      QuoteCollaborationApprovedResult result,
      QuoteCollaborationProductTask sourceTask,
      QuoteCollaborationTask master,
      Long oaFormItemId) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    putText(data, "collaborationNo", master.getCollaborationNo());
    putText(data, "oaNo", master.getOaNo());
    data.put("oaFormItemId", oaFormItemId);
    data.put("approvedResultId", result.getId());
    putText(data, "approvedResultNo", result.getResultNo());
    putText(data, "resultType", result.getResultType());
    putText(data, "productCode", result.getProductCode());
    putText(data, "applicableOrgCode", result.getApplicableOrgCode());
    if (result.getValidUntil() != null) {
      data.put("validUntil", result.getValidUntil().toString());
    }
    data.put("sourceProductTaskId", sourceTask.getId());
    putText(data, "statusCode", "READY");
    return command(
        "APPROVED_RESULT", result.getId(), result.getResultNo(), 1,
        OaCollaborationEventType.APPROVED_RESULT_REUSED,
        "OA_ITEM:" + oaFormItemId, data);
  }

  public Optional<CollaborationEventCommand> productTransition(
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      ProductTaskStatus source,
      ProductAction action,
      CollaborationNextAction nextAction) {
    return productTransition(task, master, source, action, nextAction, List.of());
  }

  public Optional<CollaborationEventCommand> productTransition(
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      ProductTaskStatus source,
      ProductAction action,
      CollaborationNextAction nextAction,
      List<QuoteCollaborationGap> validationIssues) {
    OaCollaborationEventType eventType = productEvent(source, action);
    if (eventType == null) {
      return Optional.empty();
    }
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    putText(data, "collaborationNo", master.getCollaborationNo());
    putText(data, "oaNo", master.getOaNo());
    data.put("productTaskId", task.getId());
    putText(data, "productTaskNo", task.getProductTaskNo());
    putText(data, "productCode", task.getProductCode());
    data.put("taskVersion", task.getTaskVersion());
    data.put("statusCode", task.getTaskStatus());
    data.put("openGapCount", valueOrZero(task.getOpenGapCount()));
    data.put("needBom", enabled(task.getNeedBom()));
    data.put("needPackage", enabled(task.getNeedPackage()));
    data.put("needPrice", enabled(task.getNeedPrice()));
    putText(data, "nextAction", nextAction == null ? null : nextAction.name());
    putAssignee(data, task.getCurrentAssigneeUserId(), task.getCurrentAssigneeName());
    if (action == ProductAction.FAIL_TECH_VALIDATION && validationIssues != null) {
      var openIssues = validationIssues.stream()
          .filter(issue -> issue != null && !"OBSOLETE".equals(issue.getGapStatus()))
          .limit(20)
          .toList();
      data.put("validationIssueCount", openIssues.size());
      var issueArray = data.putArray("validationIssues");
      for (QuoteCollaborationGap issue : openIssues) {
        ObjectNode item = issueArray.addObject();
        putText(item, "code", issue.getReasonCode());
        putText(item, "message", issue.getReasonMessage());
        putText(item, "nodeKey", issue.getBomNodeKey());
        putText(item, "bomPath", issue.getBomPath());
        putText(item, "materialCode", issue.getMaterialCode());
      }
    }
    return Optional.of(command(
        "PRODUCT_TASK", task.getId(), task.getProductTaskNo(), task.getTaskVersion(),
        eventType, data));
  }

  private CollaborationEventCommand productLifecycleEvent(
      QuoteCollaborationProductTask task,
      QuoteCollaborationTask master,
      OaCollaborationEventType eventType,
      CollaborationNextAction nextAction,
      Long oaFormItemId) {
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    putText(data, "collaborationNo", master.getCollaborationNo());
    putText(data, "oaNo", master.getOaNo());
    data.put("productTaskId", task.getId());
    putText(data, "productTaskNo", task.getProductTaskNo());
    putText(data, "productCode", task.getProductCode());
    data.put("taskVersion", task.getTaskVersion());
    putText(data, "statusCode", task.getTaskStatus());
    putText(data, "primaryScope", task.getPrimaryScope());
    data.put("openGapCount", valueOrZero(task.getOpenGapCount()));
    data.put("needBom", enabled(task.getNeedBom()));
    data.put("needPackage", enabled(task.getNeedPackage()));
    data.put("needPrice", enabled(task.getNeedPrice()));
    putText(data, "nextAction", nextAction == null ? null : nextAction.name());
    if (oaFormItemId != null) {
      data.put("oaFormItemId", oaFormItemId);
    }
    putAssignee(data, task.getCurrentAssigneeUserId(), task.getCurrentAssigneeName());
    return command(
        "PRODUCT_TASK", task.getId(), task.getProductTaskNo(), task.getTaskVersion(),
        eventType, oaFormItemId == null ? null : "OA_ITEM:" + oaFormItemId, data);
  }

  public Optional<CollaborationEventCommand> masterTransition(
      QuoteCollaborationTask task,
      MasterStatus source,
      MasterAction action) {
    OaCollaborationEventType eventType = masterEvent(source, action);
    if (eventType == null) {
      return Optional.empty();
    }
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("collaborationTaskId", task.getId());
    putText(data, "collaborationNo", task.getCollaborationNo());
    putText(data, "oaNo", task.getOaNo());
    putText(data, "accountingMonth", task.getAccountingMonth());
    data.put("taskVersion", task.getTaskVersion());
    data.put("statusCode", task.getMasterStatus());
    data.put("ownedProductCount", valueOrZero(task.getOwnedProductCount()));
    data.put("techSubmittedCount", valueOrZero(task.getTechSubmittedCount()));
    data.put("returnedProductCount", valueOrZero(task.getReturnedProductCount()));
    data.put("readyProductCount", valueOrZero(task.getReadyProductCount()));
    return Optional.of(command(
        "COLLABORATION_TASK", task.getId(), task.getCollaborationNo(), task.getTaskVersion(),
        eventType, data));
  }

  private static OaCollaborationEventType productEvent(
      ProductTaskStatus source, ProductAction action) {
    return switch (action) {
      case START_BOM, START_PACKAGE, START_PRICE,
          FAIL_TECH_VALIDATION, RETRY_BOM, RETRY_PACKAGE, RETRY_PRICE,
          CONTINUE_PRICE_AFTER_BOM, CONTINUE_PRICE_AFTER_PACKAGE ->
          OaCollaborationEventType.TECH_TASK_UPDATED;
      case SUBMIT_TECH -> OaCollaborationEventType.TECH_TASK_COMPLETED;
      case REJECT_TO_TECH, RETURN_BUSINESS_GAP_TO_TECH ->
          OaCollaborationEventType.TECH_TASK_REOPENED;
      case FAIL_PUBLISH_OR_REPRICE -> OaCollaborationEventType.SYSTEM_SYNC_FAILED;
      case START_COSTING -> OaCollaborationEventType.COSTING_STARTED;
      case COMPLETE_COSTING -> OaCollaborationEventType.COSTING_COMPLETED;
      case CANCEL -> OaCollaborationEventType.COLLABORATION_CANCELLED;
      case ROUTE_TO_FINANCE, APPROVE_FOR_PUBLISHING,
          RETRY_PUBLISH_OR_REPRICE, MARK_READY_FOR_COSTING -> null;
    };
  }

  private static OaCollaborationEventType masterEvent(
      MasterStatus source, MasterAction action) {
    return switch (action) {
      case ROUTE_TO_FINANCE -> source == MasterStatus.PARTIAL_RETURN
          ? OaCollaborationEventType.FINANCE_REVIEW_RESUMED
          : OaCollaborationEventType.FINANCE_REVIEW_READY;
      case FINANCE_APPROVE, RETRY_PUBLISH ->
          OaCollaborationEventType.FINANCE_REVIEW_PROCESSING;
      case MARK_PUBLISH_FAILED -> OaCollaborationEventType.SYSTEM_SYNC_FAILED;
      case RETURN_BUSINESS_GAP_TO_TECH -> null;
      case MARK_READY_FOR_COSTING -> OaCollaborationEventType.FINANCE_REVIEW_COMPLETED;
      case MARK_COMPLETED -> OaCollaborationEventType.COSTING_COMPLETED;
      case CANCEL -> OaCollaborationEventType.COLLABORATION_CANCELLED;
      case FINANCE_REJECT -> null;
    };
  }

  private static CollaborationEventCommand command(
      String aggregateType,
      Long aggregateId,
      String aggregateNo,
      Integer aggregateVersion,
      OaCollaborationEventType eventType,
      ObjectNode data) {
    return command(
        aggregateType, aggregateId, aggregateNo, aggregateVersion, eventType, null, data);
  }

  private static CollaborationEventCommand command(
      String aggregateType,
      Long aggregateId,
      String aggregateNo,
      Integer aggregateVersion,
      OaCollaborationEventType eventType,
      String target,
      ObjectNode data) {
    return new CollaborationEventCommand(
        aggregateType, aggregateId, aggregateNo, aggregateVersion, eventType, target,
        UUID.randomUUID().toString(), OffsetDateTime.now(BUSINESS_ZONE), data);
  }

  private static void putAssignee(ObjectNode data, Long userId, String userName) {
    if (userId == null && (userName == null || userName.isBlank())) {
      return;
    }
    ObjectNode assignee = data.putObject("assignee");
    if (userId != null) {
      assignee.put("userId", userId);
    }
    putText(assignee, "userName", userName);
  }

  private static void putText(ObjectNode node, String field, String value) {
    if (value != null && !value.isBlank()) {
      node.put(field, value.trim());
    }
  }

  private static int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static boolean enabled(Integer value) {
    return value != null && value == 1;
  }
}
