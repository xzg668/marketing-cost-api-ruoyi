package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** 报价物料明细当前可替代组汇总。 */
public record QuoteBomAlternativeSummaryResponse(
    String periodMonth,
    int groupCount,
    int manualAlternativeCount,
    boolean reviewRequired,
    List<QuoteBomAlternativeGroupResponse> groups) {

  public QuoteBomAlternativeSummaryResponse {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }
}
