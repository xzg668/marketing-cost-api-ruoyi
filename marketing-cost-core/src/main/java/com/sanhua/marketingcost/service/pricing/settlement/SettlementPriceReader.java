package com.sanhua.marketingcost.service.pricing.settlement;

import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import java.util.List;
import java.util.Map;

/** core 消费、biz 实现的最小读取端口；复用已有核算结果与冻结证据协议。 */
public interface SettlementPriceReader {
  String RESULT_REF_TYPE = "SETTLEMENT_PRICE";
  Map<SettlementPriceRequest, PriceResolveResult> resolveBatch(List<SettlementPriceRequest> requests);

  default PriceResolveResult resolve(SettlementPriceRequest request) {
    return resolveBatch(List.of(request)).get(request);
  }
}
