package com.sanhua.marketingcost.service.bomalternative;

import java.math.BigDecimal;

/** 替代组候选快照；候选字段不参与替代组稳定键计算。 */
public record BomAlternativeCandidate(
    Long rawHierarchyNodeId,
    String materialCode,
    String materialName,
    String materialSpec,
    BomChildType childType,
    BigDecimal qtyPerParent,
    String path,
    String sourceImportBatchId,
    String sourceBuildBatchId) {
}
