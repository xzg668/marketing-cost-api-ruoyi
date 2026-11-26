package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;

public interface QuoteCostingWorkbenchService {

  QuoteCostingWorkbenchResponse getWorkbench(String oaNo, Long oaFormItemId);

  void prepareModelProduct(String oaNo, Long itemId, String month, String model, String code);

  QuoteCostingWorkbenchResponse launchWorkbench(String oaNo, Long oaFormItemId);
}
