package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;

public interface PackageComponentPriceService {

  /** 只读计算包装价格，不生成快照、价格行、明细或缺口。 */
  PackagePriceResult calculatePrice(PackagePriceRequest request);

  PackagePriceResult ensurePrice(PackagePriceRequest request);

  PackagePriceDetailResult getPriceDetail(Long priceId);
}
