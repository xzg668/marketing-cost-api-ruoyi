package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** 保存标准/替代选择并原子重建报价物料明细后的结果。 */
public record QuoteBomAlternativeSelectionResponse(
    String alternativeGroupKey,
    Integer selectionVersion,
    String selectedMaterialCode,
    String selectedChildType,
    String selectionSource,
    boolean idempotent,
    boolean rebuilt,
    boolean manualChangesDiscarded,
    int rowsBefore,
    int rowsAfter,
    String newBuildBatchId,
    List<String> workflowInvalidated) {

  public QuoteBomAlternativeSelectionResponse {
    workflowInvalidated =
        workflowInvalidated == null
            ? List.of()
            : List.copyOf(workflowInvalidated);
  }
}
