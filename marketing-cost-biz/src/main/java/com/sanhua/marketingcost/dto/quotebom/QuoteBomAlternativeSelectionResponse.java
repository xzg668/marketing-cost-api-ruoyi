package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** 保存标准/替代选择后的结果；选择变化时提示重新核算。 */
public record QuoteBomAlternativeSelectionResponse(
    String alternativeGroupKey,
    Integer selectionVersion,
    String selectedMaterialCode,
    String selectedChildType,
    String selectionSource,
    boolean idempotent,
    boolean recalculationRequired,
    List<String> workflowInvalidated) {

  public QuoteBomAlternativeSelectionResponse {
    workflowInvalidated =
        workflowInvalidated == null
            ? List.of()
            : List.copyOf(workflowInvalidated);
  }
}
