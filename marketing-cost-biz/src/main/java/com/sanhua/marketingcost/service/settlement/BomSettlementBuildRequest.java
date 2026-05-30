package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 统一 BOM 结算行生成请求；只描述一次内存构建，不包含任何数据库写入动作。 */
public record BomSettlementBuildRequest(
    String oaNo,
    String topProductCode,
    LocalDate asOfDate,
    String periodMonth,
    String buildBatchId,
    LocalDateTime builtAt,
    String businessUnitType,
    String bomPurpose,
    List<BomSettlementNode> nodes,
    List<BomSettlementRule> settlementRules,
    List<BomSettlementByproduct> byproducts,
    List<BomSettlementScrapRef> scrapRefs,
    List<BomByproductCostRule> byproductRules) {

  public BomSettlementBuildRequest(
      String oaNo,
      String topProductCode,
      LocalDate asOfDate,
      String periodMonth,
      String buildBatchId,
      LocalDateTime builtAt,
      String businessUnitType,
      String bomPurpose,
      List<BomSettlementNode> nodes,
      List<BomSettlementRule> settlementRules) {
    this(
        oaNo,
        topProductCode,
        asOfDate,
        periodMonth,
        buildBatchId,
        builtAt,
        businessUnitType,
        bomPurpose,
        nodes,
        settlementRules,
        List.of(),
        List.of(),
        List.of());
  }

  public BomSettlementBuildRequest {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    settlementRules = settlementRules == null ? List.of() : List.copyOf(settlementRules);
    byproducts = byproducts == null ? List.of() : List.copyOf(byproducts);
    scrapRefs = scrapRefs == null ? List.of() : List.copyOf(scrapRefs);
    byproductRules = byproductRules == null ? List.of() : List.copyOf(byproductRules);
  }
}
