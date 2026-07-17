package com.sanhua.marketingcost.dto.financequote;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import java.math.BigDecimal;

/** 单产品 OA/财务 Cu 双价格准备批次生成结果。 */
public record FinancePricePrepareGenerateResult(
    String sourcePrepareNo,
    String financePrepareNo,
    String scenarioGroupNo,
    Long financeBasePriceId,
    BigDecimal financeCuPricePerKg,
    PricePrepareGenerateResult prepareResult) {}
