package com.sanhua.marketingcost.dto.quotebom;

/** 最终树预览所采用的一组标准/替代选择。 */
public record QuoteEffectiveBomAlternativeResponse(
    String alternativeGroupKey,
    String standardMaterialCode,
    String selectedMaterialCode,
    String selectedChildType,
    String selectionSource,
    Integer selectionVersion,
    Long selectionId,
    boolean persisted) {}
