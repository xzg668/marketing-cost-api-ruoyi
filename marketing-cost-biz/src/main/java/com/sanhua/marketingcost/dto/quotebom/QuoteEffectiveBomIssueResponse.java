package com.sanhua.marketingcost.dto.quotebom;

/** 阻止当前产品最终树确认的可读问题。 */
public record QuoteEffectiveBomIssueResponse(
    String issueCode,
    String materialCode,
    String sourcePath,
    String message) {}
