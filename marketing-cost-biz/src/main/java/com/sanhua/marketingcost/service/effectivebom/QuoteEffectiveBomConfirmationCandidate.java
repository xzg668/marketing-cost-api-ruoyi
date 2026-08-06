package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端重新计算并校验后的单产品确认输入，禁止由前端直接提交节点。 */
public record QuoteEffectiveBomConfirmationCandidate(
    QuoteEffectiveBomResponse response,
    QuoteBomMonthlyFreezeKey monthlyKey,
    Map<String, Long> alternativeSelectionIdByGroupKey,
    EffectiveBomVariantInput candidateVariant) {

  public QuoteEffectiveBomConfirmationCandidate {
    alternativeSelectionIdByGroupKey =
        alternativeSelectionIdByGroupKey == null
            ? Map.of()
            : Collections.unmodifiableMap(
                new LinkedHashMap<>(alternativeSelectionIdByGroupKey));
  }

  public boolean alreadyFrozen() {
    return candidateVariant == null;
  }
}
