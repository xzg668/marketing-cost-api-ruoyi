package com.sanhua.marketingcost.service.rule;

/** BOM 结算规则使用的正式料品档案属性。 */
public record BomRuleMaterialAttributes(
    String mainCategoryCode,
    String purchaseCategory) {}
