package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeAdjustRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationActionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeImportMissingRequest;

public interface QuotePriceTypeConfirmationService {

  QuotePriceTypeConfirmationResponse getConfirmation(
      String oaNo, Long oaFormItemId, String periodMonth);

  QuotePriceTypeConfirmationActionResponse importMissing(
      String oaNo, Long oaFormItemId, QuotePriceTypeImportMissingRequest request);

  QuotePriceTypeConfirmationActionResponse adjustPriceType(
      String oaNo, Long oaFormItemId, QuotePriceTypeAdjustRequest request);

  QuotePriceTypeConfirmationActionResponse confirm(
      String oaNo, Long oaFormItemId, QuotePriceTypeConfirmRequest request);
}
