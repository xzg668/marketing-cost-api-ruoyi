package com.sanhua.marketingcost.service.effectivebom;

/** 阻止当前产品生成报价物料的纯构建问题。 */
public record EffectiveBomBlockIssue(
    String issueCode,
    String materialCode,
    String sourcePath,
    String message) {}
