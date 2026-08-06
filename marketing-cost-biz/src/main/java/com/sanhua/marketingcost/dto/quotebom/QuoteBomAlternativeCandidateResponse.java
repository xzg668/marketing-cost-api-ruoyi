package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;

/** 当前正式 BOM 中某一标准/替代组的权威候选。 */
public record QuoteBomAlternativeCandidateResponse(
    String materialCode,
    String materialName,
    String materialSpec,
    String childType,
    BigDecimal qtyPerParent,
    String sourceImportBatchId,
    String sourceBuildBatchId,
    boolean selected) {
}
