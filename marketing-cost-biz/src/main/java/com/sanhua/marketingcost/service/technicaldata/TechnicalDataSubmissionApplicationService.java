package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;

public interface TechnicalDataSubmissionApplicationService {
  TechnicalDataTaskValidationResponse validate(Long taskId, Long assigneeUserId, TechnicalDataActor actor);

  TechnicalDataTaskSubmissionResponse submit(
      Long taskId, TechnicalDataTaskSubmissionRequest request, TechnicalDataActor actor);
}
