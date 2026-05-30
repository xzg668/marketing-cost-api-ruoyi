package com.sanhua.marketingcost.dto.quotebom;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record QuoteBomCostingRowDto(
    Long id,
    String oaNo,
    String topProductCode,
    String parentCode,
    String materialCode,
    String materialName,
    String materialSpec,
    String shapeAttr,
    String sourceCategory,
    String costElementCode,
    String bomPurpose,
    String bomVersion,
    Integer level,
    String path,
    BigDecimal qtyPerParent,
    BigDecimal qtyPerTop,
    Integer isCostingRow,
    Integer subtreeCostRequired,
    Long rawHierarchyNodeId,
    Long matchedSettlementRuleId,
    String settlementRowType,
    String matchedSettlementRuleName,
    String matchedRuleAction,
    String matchedRuleRemark,
    String matchedRuleType,
    String matchedRuleValue,
    String sourceSummary,
    List<QuoteBomCostingRowSourceRefDto> sourceRefs,
    String buildBatchId,
    LocalDateTime builtAt,
    String periodMonth,
    LocalDate asOfDate,
    LocalDate rawVersionEffectiveFrom,
    String businessUnitType,
    LocalDateTime updatedAt) {}
