package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;

public record QuoteBomSourceLineDto(
    Long sourceId,
    Integer lineNo,
    Integer level,
    String topProductCode,
    String parentCode,
    String materialCode,
    String materialName,
    String materialSpec,
    String materialModel,
    String drawingNo,
    String shapeAttr,
    String mainCategoryCode,
    String unit,
    String sourceCategory,
    String costElementCode,
    String bomPurpose,
    String bomVersion,
    BigDecimal qtyPerParent,
    BigDecimal qtyPerTop,
    BigDecimal parentBaseQty,
    String path,
    Integer sortSeq,
    Long sourceRawHierarchyId,
    Long sourceU9BomId,
    Integer manualFlag) {}
