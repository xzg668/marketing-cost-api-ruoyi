package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;

public interface SupplementBomReadService {

  SupplementBomReadResult readApproved(
      String quoteProductCode, String productType, String supplementScope, String periodMonth);
}
