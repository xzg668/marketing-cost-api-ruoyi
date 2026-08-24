package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomCostingCandidate;

/** 核算工作台第 1 步的单产品本次计价 BOM 应用服务。 */
public interface QuoteEffectiveBomApplicationService {

  QuoteEffectiveBomResponse getEffectiveBom(String oaNo, Long oaFormItemId);

  QuoteEffectiveBomResponse rebuildPreview(String oaNo, Long oaFormItemId);

  QuoteEffectiveBomResponse previewAlternative(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String alternativeGroupKey,
      String selectedMaterialCode);

  QuoteEffectiveBomCostingCandidate prepareCostingCandidate(
      String oaNo, Long oaFormItemId);
}
