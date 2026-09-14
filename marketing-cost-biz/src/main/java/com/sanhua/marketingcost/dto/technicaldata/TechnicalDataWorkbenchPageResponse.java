package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataWorkbenchPageResponse(
    long total,
    int current,
    int size,
    List<TechnicalDataWorkbenchRowResponse> records) {}
