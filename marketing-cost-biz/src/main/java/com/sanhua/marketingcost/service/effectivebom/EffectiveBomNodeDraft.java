package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import java.math.BigDecimal;

/** 尚未落库的最终有效 BOM 节点快照。 */
public record EffectiveBomNodeDraft(
    String nodeKey,
    String parentNodeKey,
    Integer nodeLevel,
    Integer sortSeq,
    String nodePath,
    String materialCode,
    String materialName,
    String materialSpec,
    String priceOrgCode,
    BigDecimal qtyPerParent,
    BigDecimal qtyPerTop,
    String sourceMaterialShape,
    QuoteMaterialShape effectiveMaterialShape,
    MaterialQuoteShapeSource shapeResolutionSource,
    Long shapePolicyId,
    String shapePolicyFingerprint,
    Long selectedSupplierRatioId,
    String selectedSupplierCode,
    String selectedSupplierName,
    BigDecimal selectedSupplyRatio,
    String alternativeGroupKey,
    String alternativeChildType,
    String alternativeSelectionSource,
    String sourceBomType,
    String sourceBomBatchId,
    Long sourceHierarchyId,
    String sourceNodePath) {}
