package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationHistoryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationSummaryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;

public interface QuoteItemCollaborationProjectionService {
  QuoteCollaborationSummaryResponse summary(String oaNo);
  QuoteItemCollaborationResponse project(String oaNo, Long itemId);
  QuoteCollaborationHistoryResponse history(String oaNo, Long itemId);
}
