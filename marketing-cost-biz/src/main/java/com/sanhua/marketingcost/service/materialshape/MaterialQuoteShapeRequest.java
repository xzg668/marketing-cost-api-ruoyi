package com.sanhua.marketingcost.service.materialshape;

/** 单个报价 BOM 节点的形态解析输入。U9 形态来自月度原始 BOM，不读取实时主档。 */
public record MaterialQuoteShapeRequest(
    String materialOrgCode,
    String materialCode,
    String accountingMonth,
    String sourceU9Shape) {}
