package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest;
import com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcResult;

/** 单产品财务 Cu 成本核算、OA Cu 差额及最终报价的统一编排入口。 */
public interface QuoteCuAdjustmentCalcService {

  QuoteCuAdjustmentCalcResult calculate(QuoteCuAdjustmentCalcRequest request);
}
