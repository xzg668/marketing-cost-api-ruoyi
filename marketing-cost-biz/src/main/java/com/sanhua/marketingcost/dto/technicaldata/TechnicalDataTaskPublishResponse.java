package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalDataTaskPublishResponse(
    String action,
    Long replacedTaskId,
    TechnicalDataTaskResponse task) {}
