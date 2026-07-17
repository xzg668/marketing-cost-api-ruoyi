package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;

public interface PricePrepareService {

  /** 只读计算价格准备结果，不新增、更新或失效任何业务数据。 */
  PricePrepareCalculationResult calculate(PricePrepareGenerateRequest request);

  PricePrepareGenerateResult generate(PricePrepareGenerateRequest request);
}
