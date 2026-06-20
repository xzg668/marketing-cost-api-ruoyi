package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;

public interface QuoteBomConfirmationService {

  QuoteBomConfirmResponse confirm(String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request);

  QuoteBomConfirmResponse cancelConfirm(
      String oaNo, Long oaFormItemId, QuoteBomCancelConfirmRequest request);
}
