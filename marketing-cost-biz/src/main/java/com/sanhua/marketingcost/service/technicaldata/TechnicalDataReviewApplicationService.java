package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewItemDetailResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPageResponse;

public interface TechnicalDataReviewApplicationService {
  TechnicalDataTaskPageResponse mine(
      int current, int size, String taskStatus, String accountingMonth, TechnicalDataActor actor);

  TechnicalDataReviewTaskResponse detail(Long taskId, TechnicalDataActor actor);

  TechnicalDataReviewItemDetailResponse itemDetail(
      Long taskId, Long itemId, TechnicalDataActor actor);

  TechnicalDataReviewDecisionResponse decide(
      Long taskId,
      Long itemId,
      String decision,
      TechnicalDataReviewDecisionRequest request,
      TechnicalDataActor actor);
}
