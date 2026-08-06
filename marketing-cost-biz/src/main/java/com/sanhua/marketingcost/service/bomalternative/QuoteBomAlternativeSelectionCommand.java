package com.sanhua.marketingcost.service.bomalternative;

/** 报价员保存标准/替代选择的并发保护命令。 */
public record QuoteBomAlternativeSelectionCommand(
    QuoteBomAlternativeSelectionScope scope,
    String alternativeGroupKey,
    String selectedMaterialCode,
    Integer expectedSelectionVersion,
    String expectedBuildBatchId,
    String selectedBy,
    String selectionRemark) {
}
