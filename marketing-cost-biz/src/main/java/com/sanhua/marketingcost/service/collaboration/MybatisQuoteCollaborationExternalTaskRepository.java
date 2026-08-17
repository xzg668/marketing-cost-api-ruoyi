package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationExternalTask;
import com.sanhua.marketingcost.mapper.QuoteCollaborationExternalTaskMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisQuoteCollaborationExternalTaskRepository
    implements QuoteCollaborationExternalTaskRepository {

  private final QuoteCollaborationExternalTaskMapper mapper;

  public MybatisQuoteCollaborationExternalTaskRepository(
      QuoteCollaborationExternalTaskMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public QuoteCollaborationExternalTask save(QuoteCollaborationExternalTask task) {
    if (task == null) {
      throw new IllegalArgumentException("外部协作任务不能为空");
    }
    if (mapper.insert(task) != 1) {
      throw new CollaborationPersistenceException("保存外部协作任务失败");
    }
    return task;
  }

  @Override
  public List<QuoteCollaborationExternalTask> findCurrentByAssignee(
      String assigneeUserId, List<String> statuses, CollaborationScope scope) {
    if (statuses == null || statuses.isEmpty()) {
      throw new IllegalArgumentException("外部任务状态集合不能为空");
    }
    return mapper.selectCurrentByAssigneeAndStatuses(
        CollaborationScope.requireText(assigneeUserId, "外部责任人"),
        scope.businessUnitType(), scope.applicableOrgCode(), List.copyOf(statuses));
  }
}
