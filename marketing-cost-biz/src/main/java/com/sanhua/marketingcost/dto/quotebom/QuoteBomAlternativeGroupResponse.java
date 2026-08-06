package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** 报价产品当前可达的一个标准/替代候选组。 */
public record QuoteBomAlternativeGroupResponse(
    String alternativeGroupKey,
    String parentMaterialCode,
    String parentMaterialName,
    String parentPath,
    Integer childSeq,
    String processSeq,
    String bomPurpose,
    String bomVersion,
    Integer selectionVersion,
    String selectionSource,
    String selectionStatus,
    String selectedMaterialCode,
    String selectedChildType,
    String sourceBuildBatchId,
    boolean reviewRequired,
    boolean selectionPersisted,
    List<QuoteBomAlternativeCandidateResponse> candidates) {

  public QuoteBomAlternativeGroupResponse {
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
  }
}
