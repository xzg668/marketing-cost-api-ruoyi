package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;

public interface TechnicalDataOaIntegrationService {
  boolean enabled();
  TechnicalDataTaskResponse publishInitial(Long taskId, TechnicalDataActor actor);
  TechnicalDataTaskResponse retry(
      Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);
  TechnicalDataOaCallbackResponse callback(TechnicalDataOaCallbackRequest request);
}
