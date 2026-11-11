package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;

public interface QuotePricePrepareWorkbenchService {

  QuotePricePrepareWorkbenchResponse getPricePrepare(
      String oaNo, Long oaFormItemId, String periodMonth);

  QuotePricePrepareWorkbenchResponse checkPriceSources(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request);

  /** Returns only a complete, still-current preparation; null means use normal preparation. */
  QuotePricePrepareWorkbenchResponse currentForCosting(
      String oaNo, Long oaFormItemId, String periodMonth);

  QuotePricePrepareWorkbenchResponse generate(
      String oaNo, Long oaFormItemId, QuotePricePrepareGenerateRequest request);
}
