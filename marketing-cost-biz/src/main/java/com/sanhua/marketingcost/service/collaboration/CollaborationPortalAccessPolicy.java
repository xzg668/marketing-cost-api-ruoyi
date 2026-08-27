package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.security.CollaborationPortalAuthentication;
import com.sanhua.marketingcost.security.CollaborationPortalModule;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 外部协作任务边界策略。
 *
 * <p>普通登录沿用本人任务校验；系统内协作直达链接在此基础上再限制主任务和业务模块。
 */
@Component
public class CollaborationPortalAccessPolicy {

  public List<QuoteCollaborationProductTask> visibleTasks(
      List<QuoteCollaborationProductTask> tasks) {
    CollaborationPortalAuthentication.Scope scope =
        CollaborationPortalAuthentication.currentScope();
    if (scope == null) return tasks;
    return tasks.stream().filter(task -> belongsTo(task, scope.collaborationId())).toList();
  }

  public QuoteCollaborationProductTask requireTask(QuoteCollaborationProductTask task) {
    return requireTask(task, null);
  }

  public QuoteCollaborationProductTask requireTask(
      QuoteCollaborationProductTask task, CollaborationPortalModule module) {
    CollaborationPortalAuthentication.Scope scope =
        CollaborationPortalAuthentication.currentScope();
    if (scope == null) return task;
    if (!belongsTo(task, scope.collaborationId())
        || (module != null && !scope.allows(module))) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_NOT_FOUND,
          "当前协作链接不能访问该任务或业务模块");
    }
    return task;
  }

  private static boolean belongsTo(QuoteCollaborationProductTask task, Long collaborationId) {
    return task != null && Objects.equals(task.getOriginCollaborationId(), collaborationId);
  }
}
