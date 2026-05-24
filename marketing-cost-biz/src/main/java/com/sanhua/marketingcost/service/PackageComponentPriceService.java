package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;

public interface PackageComponentPriceService {

  PackagePriceResult ensurePrice(PackagePriceRequest request);

  PackagePriceDetailResult getPriceDetail(Long priceId);
}
