package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPrepareRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchPageResponse;

public interface TechnicalDataTaskApplicationService {
  TechnicalDataTaskResponse prepare(
      TechnicalDataTaskPrepareRequest request, TechnicalDataActor actor);

  TechnicalDataTaskPublishResponse publish(
      TechnicalDataTaskPublishRequest request, TechnicalDataActor actor);

  TechnicalDataWorkbenchPageResponse workbench(
      int current,
      int size,
      String taskStatus,
      String accountingMonth,
      String keyword,
      TechnicalDataActor actor);

  TechnicalDataTaskResponse detail(Long taskId, TechnicalDataActor actor);
}
