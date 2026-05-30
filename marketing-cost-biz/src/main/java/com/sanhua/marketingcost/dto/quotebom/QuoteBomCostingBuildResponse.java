package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record QuoteBomCostingBuildResponse(
    Long preparationId,
    Long taskId,
    Long oaFormItemId,
    String oaNo,
    String quoteProductCode,
    String productType,
    String periodMonth,
    String buildBatchId,
    int costingRowsWritten,
    int sourceRefsWritten,
    int subtreeRequiredCount,
    Map<String, Integer> sourceTypeCounts,
    List<String> warnings,
    LocalDateTime builtAt) {}
