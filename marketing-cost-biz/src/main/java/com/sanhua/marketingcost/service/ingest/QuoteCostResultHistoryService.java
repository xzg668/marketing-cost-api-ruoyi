package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteMonthlyCostResultDetailResponse;

public interface QuoteCostResultHistoryService {
  QuoteCostResultHistoryResponse listHistory(String oaNo, Long oaFormItemId);

  QuoteMonthlyCostResultDetailResponse getMonthlyResult(
      String oaNo, Long oaFormItemId, Long resultId);
}
