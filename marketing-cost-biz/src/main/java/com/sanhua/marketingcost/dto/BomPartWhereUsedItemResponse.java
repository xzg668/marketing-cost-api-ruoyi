package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 物料使用查询的一条受影响顶层产品。 */
public record BomPartWhereUsedItemResponse(
    String priceOrgCode,
    String partCode,
    String partName,
    String partSpec,
    String topProductCode,
    String topProductName,
    String topBomVersion,
    String bomPurpose,
    BigDecimal totalQtyPerTop,
    Long bomPathCount,
    Integer minLevel,
    Integer maxLevel,
    boolean hasLeafOccurrence,
    boolean hasNonLeafOccurrence,
    String samplePath,
    String shapeAttr,
    String sourceCategory,
    String costElementCode,
    LocalDate snapshotDate) {}
