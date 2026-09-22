package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;

public interface TechnicalDataOaIntegrationService {
  boolean enabled();
  void queueDispatch(QuoteTechTask task, Long coordinatorId, java.util.Map<String, Long> moduleAssignees, TechnicalDataActor actor);
  void queueCancellation(QuoteTechTask task, TechnicalDataActor actor);
  void queueSourceRefresh(QuoteTechTask task);
  void markUnconfirmed(OaMessageRepository.Message message, boolean rejected, String reason);
  void retry(Long taskId, TechnicalDataAdminActionRequest request, TechnicalDataActor actor);
  void acceptReceipt(OaMessageRepository.Message message, TechnicalDataOaGateway.Receipt receipt);
}
