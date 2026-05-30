package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

public record QuoteBomCostingProductDto(
    String oaNo,
    String topProductCode,
    String periodMonth,
    Long rowCount,
    Long ruleHitCount,
    Long subtreeCostRequiredCount,
    String buildBatchId,
    LocalDateTime latestBuiltAt) {}
