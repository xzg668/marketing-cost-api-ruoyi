package com.sanhua.marketingcost.dto.quotebom;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 报价员选择标准件或替代件的业务意图。
 *
 * <p>名称、类型、规格和用量不从前端接收，统一从当前正式 BOM 候选重新取得。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteBomAlternativeSelectionRequest(
    String periodMonth,
    String selectedMaterialCode,
    Integer expectedSelectionVersion,
    String expectedBuildBatchId,
    boolean confirmDiscardManualChanges,
    String selectionRemark) {
}
