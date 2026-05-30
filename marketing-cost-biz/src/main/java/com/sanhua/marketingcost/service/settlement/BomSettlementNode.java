package com.sanhua.marketingcost.service.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 统一 BOM 结算引擎输入节点；调用方负责把正式 BOM、补录 BOM、包装参考先归一成该结构。 */
public record BomSettlementNode(
    Long sourceNodeId,
    String topProductCode,
    String parentCode,
    String materialCode,
    Integer level,
    String path,
    BigDecimal qtyPerParent,
    BigDecimal qtyPerTop,
    String materialName,
    String materialSpec,
    String shapeAttr,
    String productionCategory,
    String costElementCode,
    String materialCategoryCode,
    String mainCategoryName,
    String purchaseCategory,
    String bomPurpose,
    String bomVersion,
    Integer u9IsCostFlag,
    Integer isLeaf,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    LocalDate rawVersionEffectiveFrom,
    String businessUnitType,
    BomSettlementSourceRef sourceRef) {

  public boolean leaf() {
    return Integer.valueOf(1).equals(isLeaf);
  }
}
