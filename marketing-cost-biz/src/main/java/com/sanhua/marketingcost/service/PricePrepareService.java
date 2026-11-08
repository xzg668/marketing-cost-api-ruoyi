package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;

public interface PricePrepareService {

  /** 只读计算价格准备结果，不新增、更新或失效任何业务数据。 */
  PricePrepareCalculationResult calculate(PricePrepareGenerateRequest request);

  /** 补录任务按其已保存月份只读检查，不切换月份、不采用月价。仅供已校验任务范围的内部调用。 */
  PricePrepareCalculationResult calculateForTechnical(PricePrepareGenerateRequest request);

  PricePrepareGenerateResult generate(PricePrepareGenerateRequest request);

  /** Initial actual ordinary-material demands, after each customer's effective BOM is prepared. */
  com.sanhua.marketingcost.service.pricing.monthly.MonthlyQuotationPrices.Demand repriceDemand(
      com.sanhua.marketingcost.service.reprice.MonthlyRepriceCalculation input, String effectiveBuild);

  PricePrepareGenerateResult generateForReprice(PricePrepareGenerateRequest request,
      com.sanhua.marketingcost.service.reprice.MonthlyRepriceCalculation input, String effectiveBuild);

}
