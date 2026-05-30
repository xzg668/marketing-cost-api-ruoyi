package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;

public interface FormalBomReadService {

  FormalBomReadResult read(String productCode, String periodMonth, String bomPurpose);
}
