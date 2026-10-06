package com.sanhua.marketingcost.dto.technicaldata;

/** 其他报价已在办理或已提交的原资料。提交状态不等同于本次核算已完成取数。 */
public record TechnicalDataSharedModuleInfo(String moduleType, String status, Long sourceTaskId,
    Long sourceProductId, Long sourceVersionId, String sourceFingerprint, String assigneeName, String message) {}
