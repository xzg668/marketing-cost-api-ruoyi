package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;

public interface PricePrepareService {

  PricePrepareGenerateResult generate(PricePrepareGenerateRequest request);
}
