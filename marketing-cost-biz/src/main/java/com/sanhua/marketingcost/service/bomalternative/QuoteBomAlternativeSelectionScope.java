package com.sanhua.marketingcost.service.bomalternative;

/** 报价 BOM 替代选择的完整隔离作用域。 */
public record QuoteBomAlternativeSelectionScope(
    String oaNo,
    Long oaFormItemId,
    String topProductCode,
    String periodMonth,
    String priceOrgCode,
    String businessUnitType) {
}
