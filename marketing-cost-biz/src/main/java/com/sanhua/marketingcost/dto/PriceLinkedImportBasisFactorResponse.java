package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** “查看导入依据”中的单个动态因素来源和持久化绑定。 */
public record PriceLinkedImportBasisFactorResponse(
    String originalName,
    String rawReference,
    String sourceSheetName,
    String sourceCellRef,
    Long factorIdentityId,
    Long factorMonthlyPriceId,
    BigDecimal importedPrice,
    String systemVariable,
    String bindingSource) {
}
