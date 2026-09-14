package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;

public interface TechnicalDataAdminOperationService {
  TechnicalDataTaskResponse reassign(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);

  TechnicalDataTaskResponse startProxyEntry(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);

  TechnicalDataTaskResponse unlockDraft(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);

  TechnicalDataTaskResponse voidTask(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);
}
