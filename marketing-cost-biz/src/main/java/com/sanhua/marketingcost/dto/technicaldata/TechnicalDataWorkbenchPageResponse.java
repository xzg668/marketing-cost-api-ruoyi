package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataWorkbenchPageResponse(
    long total,
    int current,
    int size,
    boolean canViewSupplementOverview,
    Summary summary,
    List<TechnicalDataWorkbenchRowResponse> records) {
  public record Summary(long total, long pending, long submitting, long submitted, long unassigned) {}
}
