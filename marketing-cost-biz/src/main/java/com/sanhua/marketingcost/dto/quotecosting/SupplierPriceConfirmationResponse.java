package com.sanhua.marketingcost.dto.quotecosting;

/** 选择已提交与后续取价是两项独立结果，不能用取价异常冒充确认失败。 */
public record SupplierPriceConfirmationResponse(
    Long decisionId,
    String decision,
    String repriceStatus,
    String message,
    QuotePricePrepareWorkbenchResponse workbench) {}
