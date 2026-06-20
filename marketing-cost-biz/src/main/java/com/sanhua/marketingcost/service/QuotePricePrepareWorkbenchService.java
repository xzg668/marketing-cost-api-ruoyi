package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;

public interface QuotePricePrepareWorkbenchService {

  QuotePricePrepareWorkbenchResponse getPricePrepare(
      String oaNo, Long oaFormItemId, String periodMonth);

  QuotePricePrepareWorkbenchResponse generate(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request);
}
