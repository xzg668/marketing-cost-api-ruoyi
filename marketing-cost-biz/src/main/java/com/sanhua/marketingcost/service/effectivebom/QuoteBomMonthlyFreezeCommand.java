package com.sanhua.marketingcost.service.effectivebom;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 当前 OA 产品行确认或复用月度最终 BOM 的请求。 */
public record QuoteBomMonthlyFreezeCommand(
    QuoteBomMonthlyFreezeKey key,
    Long oaFormItemId,
    Long frozenBy,
    Map<String, Long> alternativeSelectionIdByGroupKey,
    EffectiveBomVariantInput candidateVariant) {

  public QuoteBomMonthlyFreezeCommand {
    alternativeSelectionIdByGroupKey =
        alternativeSelectionIdByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(alternativeSelectionIdByGroupKey));
  }
}
