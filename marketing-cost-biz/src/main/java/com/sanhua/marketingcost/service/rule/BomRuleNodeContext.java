package com.sanhua.marketingcost.service.rule;

/**
 * 新 BOM 结算规则的节点上下文。
 *
 * <p>字段名按 BSR-01 新规则种子和后续对账口径收敛，避免让新规则继续受旧
 * 新规则匹配只暴露稳定字段，避免 Matcher 依赖 raw_hierarchy 或报价 BOM 明细实体。
 */
public record BomRuleNodeContext(
    String materialCode,
    String materialName,
    String materialCategoryCode,
    String mainCategoryName,
    String purchaseCategory,
    String shapeAttr,
    String costElementCode,
    String productionCategory,
    String businessUnitType,
    String bomPurpose) {}
