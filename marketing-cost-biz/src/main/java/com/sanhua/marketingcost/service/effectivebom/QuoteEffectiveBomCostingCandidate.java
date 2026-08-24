package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端按当前规则重新计算并校验后的单产品计价 BOM 输入。 */
public record QuoteEffectiveBomCostingCandidate(
    QuoteEffectiveBomResponse response,
    Map<String, Long> alternativeSelectionIdByGroupKey,
    EffectiveBomVariantInput candidateVariant) {

  public QuoteEffectiveBomCostingCandidate {
    alternativeSelectionIdByGroupKey =
        alternativeSelectionIdByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(alternativeSelectionIdByGroupKey));
  }
}
