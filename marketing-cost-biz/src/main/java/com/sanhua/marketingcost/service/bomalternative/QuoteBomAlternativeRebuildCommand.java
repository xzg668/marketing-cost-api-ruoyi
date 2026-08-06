package com.sanhua.marketingcost.service.bomalternative;

import java.time.LocalDate;

/** 保存标准/替代选择并原子重建当前报价产品的命令。 */
public record QuoteBomAlternativeRebuildCommand(
    String oaNo,
    Long oaFormItemId,
    String topProductCode,
    String periodMonth,
    String priceOrgCode,
    String materialOrganizationCode,
    String businessUnitType,
    String bomPurpose,
    LocalDate quoteDate,
    String alternativeGroupKey,
    String selectedMaterialCode,
    Integer expectedSelectionVersion,
    String expectedBuildBatchId,
    boolean confirmDiscardManualChanges,
    String selectedBy,
    String selectionRemark) {
}
