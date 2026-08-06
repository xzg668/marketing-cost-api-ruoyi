package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;

public interface QuoteCostingWorkbenchService {

  QuoteCostingWorkbenchResponse getWorkbench(String oaNo, Long oaFormItemId);

  QuoteCostingWorkbenchResponse launchWorkbench(String oaNo, Long oaFormItemId);
}
