package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import java.io.IOException;
import java.io.OutputStream;

public interface QuoteCostRunWorkbenchService {

  QuoteCostRunWorkbenchResponse getCostRun(String oaNo, Long oaFormItemId, String periodMonth);

  QuoteCostRunWorkbenchResponse trial(
      String oaNo, Long oaFormItemId, QuoteCostRunTrialRequest request);

  QuoteCostRunSummaryResponse confirm(
      String oaNo,
      Long oaFormItemId,
      String costRunNo,
      QuoteCostRunConfirmRequest request);

  int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException;
}
