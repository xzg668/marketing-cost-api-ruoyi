package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 防止状态和当前责任人形成页面无法解释的组合。 */
@Component
public class CollaborationResponsibilityRules {

  public void requireConsistent(
      QuoteCollaborationProductTask task, QuoteCollaborationTask master) {
    ProductTaskStatus status = parse(task);
    switch (status) {
      case WAIT_TECH, BOM_IN_PROGRESS, PACKAGE_IN_PROGRESS, PRICE_IN_PROGRESS,
          TECH_VALIDATION_FAILED, RETURNED_TO_TECH -> require(
              task.getOriginalTechnicianUserId() != null
                  && Objects.equals(task.getOriginalTechnicianUserId(),
                      task.getCurrentAssigneeUserId()),
              "技术处理状态必须由原技术人员负责");
      case TECH_SUBMITTED, COMPLETED, CANCELLED -> require(
          task.getCurrentAssigneeUserId() == null && task.getCurrentAssigneeName() == null,
          "当前状态不应保留可编辑责任人");
      case WAIT_FINANCE -> require(
          master != null && master.getFinanceReviewerUserId() != null
              && Objects.equals(master.getFinanceReviewerUserId(),
                  task.getCurrentAssigneeUserId()),
          "待财务审核状态必须切到指定财务");
      case APPROVED_PUBLISHING, PUBLISH_OR_REPRICE_FAILED -> require(
          task.getCurrentAssigneeUserId() == null
              && "系统".equals(task.getCurrentAssigneeName()),
          "发布和复验阶段必须由系统负责");
      case READY_FOR_COSTING -> require(
          task.getCurrentAssigneeUserId() == null
              && "核算角色".equals(task.getCurrentAssigneeName()),
          "准备完成后必须切到核算角色");
      case COSTING -> require(task.getCurrentAssigneeUserId() != null,
          "核算中必须记录实际核算人员");
    }
  }

  private static ProductTaskStatus parse(QuoteCollaborationProductTask task) {
    if (task == null) {
      throw invalid("产品任务不能为空");
    }
    try {
      return ProductTaskStatus.valueOf(task.getTaskStatus());
    } catch (RuntimeException exception) {
      throw invalid("未知产品任务状态：" + task.getTaskStatus());
    }
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw invalid(message);
    }
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }
}
