package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalDataPriceOwner(String materialNo, Long moduleId, Long productId, Long taskId,
    String assigneeName, String moduleStatus, String taskStatus, Long versionId,
    String organizationCode, String businessUnit, String unit, String currency) {}
