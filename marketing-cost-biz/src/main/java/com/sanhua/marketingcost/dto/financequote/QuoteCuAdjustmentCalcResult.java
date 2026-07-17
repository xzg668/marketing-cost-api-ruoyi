package com.sanhua.marketingcost.dto.financequote;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.math.BigDecimal;

/** 单产品双场景编排结果；totalCost 始终是财务场景总成本。 */
public record QuoteCuAdjustmentCalcResult(
    QuoteCostRunVersion version,
    CostRunObjectResult costResult,
    QuoteCuMaterialDiffResult materialDiff,
    BigDecimal financeMaterialCost,
    BigDecimal oaMaterialCost,
    BigDecimal totalCost,
    BigDecimal cuMaterialAdjustment,
    BigDecimal finalQuoteAmount) {}
