package com.sanhua.marketingcost.service.effectivebom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 确认时创建或复用最终有效 BOM 的请求。 */
public record QuoteEffectiveBomPersistenceRequest(
    Long originMonthlySnapshotId,
    Long createdBy,
    Map<String, Long> alternativeSelectionIdByGroupKey,
    EffectiveBomVariantInput variantInput) {

  public QuoteEffectiveBomPersistenceRequest {
    alternativeSelectionIdByGroupKey =
        alternativeSelectionIdByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(alternativeSelectionIdByGroupKey));
  }
}
