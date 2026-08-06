package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;

/** 核算工作台第1步展示的单个最终有效BOM节点。 */
public record QuoteEffectiveBomNodeResponse(
    String nodeKey,
    String parentNodeKey,
    Integer nodeLevel,
    Integer sortSeq,
    String nodePath,
    String materialCode,
    String materialName,
    String materialSpec,
    BigDecimal qtyPerParent,
    BigDecimal qtyPerTop,
    String sourceMaterialShape,
    String effectiveMaterialShape,
    String shapeResolutionSource,
    Long shapePolicyId,
    String shapePolicyFingerprint,
    Long selectedSupplierRatioId,
    String selectedSupplierCode,
    String selectedSupplierName,
    BigDecimal selectedSupplyRatio,
    String alternativeGroupKey,
    String alternativeChildType,
    Long alternativeSelectionId,
    String alternativeSelectionSource,
    String sourceBomType,
    String sourceBomBatchId,
    Long sourceHierarchyId,
    String sourceNodePath) {}
