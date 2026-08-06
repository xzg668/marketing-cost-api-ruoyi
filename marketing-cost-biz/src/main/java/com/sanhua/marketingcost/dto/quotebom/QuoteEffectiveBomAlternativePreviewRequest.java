package com.sanhua.marketingcost.dto.quotebom;

/** 标准/替代方案的无落库有效 BOM 预览请求。 */
public record QuoteEffectiveBomAlternativePreviewRequest(
    String periodMonth,
    String alternativeGroupKey,
    String selectedMaterialCode) {}
