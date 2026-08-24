package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteProductCostRunTaskResponse;

public interface QuoteBatchCostRunService {

  QuoteBatchCostRunResponse submit(
      String oaNo, QuoteBatchCostRunRequest request, String submittedBy);

  QuoteBatchCostRunResponse getCurrent(String oaNo, String periodMonth);

  QuoteProductCostRunTaskResponse getCurrentItem(
      String oaNo, Long oaFormItemId, String periodMonth);
}
