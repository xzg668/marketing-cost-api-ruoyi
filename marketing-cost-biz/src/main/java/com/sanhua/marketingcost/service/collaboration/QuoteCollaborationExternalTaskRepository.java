package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationExternalTask;
import java.util.List;

public interface QuoteCollaborationExternalTaskRepository {

  QuoteCollaborationExternalTask save(QuoteCollaborationExternalTask task);

  List<QuoteCollaborationExternalTask> findCurrentByAssignee(
      String assigneeUserId, List<String> statuses, CollaborationScope scope);
}
