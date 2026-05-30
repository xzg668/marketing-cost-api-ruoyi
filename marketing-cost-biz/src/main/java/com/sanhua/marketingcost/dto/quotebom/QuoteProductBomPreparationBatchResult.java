package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteProductBomPreparationBatchResult(
    int requestedCount,
    int preparedCount,
    int readyCount,
    int needTechnicianTaskCount,
    int abnormalCount,
    List<QuoteProductBomPreparationPreview> previews) {}
