package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingBomRowUpdateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;

public interface QuoteCostingWorkbenchService {

  QuoteCostingWorkbenchResponse getWorkbench(String oaNo, Long oaFormItemId);

  QuoteCostingWorkbenchBomRowResponse updateBomRow(
      String oaNo, Long oaFormItemId, Long rowId, QuoteCostingBomRowUpdateRequest request);
}
