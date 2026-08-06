package com.sanhua.marketingcost.dto.quotebom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本次纯预览的排除节点数量及原因汇总。 */
public record QuoteEffectiveBomExclusionSummaryResponse(
    boolean available,
    Integer excludedNodeCount,
    Map<String, Integer> reasonCounts) {

  public QuoteEffectiveBomExclusionSummaryResponse {
    reasonCounts =
        reasonCounts == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(reasonCounts));
  }
}
