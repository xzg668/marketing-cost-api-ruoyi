package com.sanhua.marketingcost.service.effectivebom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 创建或复用当前有效 BOM 构建的请求。 */
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
