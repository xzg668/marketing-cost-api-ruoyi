package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;

public interface ProductPropertyAnnualSyncService {
  ProductPropertyAnnualSyncResult sync(ProductPropertyAnnualSyncRequest request);
}
