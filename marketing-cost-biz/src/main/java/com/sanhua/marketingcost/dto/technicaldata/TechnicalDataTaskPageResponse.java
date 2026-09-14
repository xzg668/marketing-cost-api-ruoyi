package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataTaskPageResponse(
    long total,
    int current,
    int size,
    List<TechnicalDataTaskSummaryResponse> records) {}
