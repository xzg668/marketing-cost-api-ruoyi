package com.sanhua.marketingcost.dto.technicaldata;

public record TechnicalPriceImportResult(
    String itemKey,
    String sheetName,
    Integer rowNumber,
    String materialNo,
    Long linkedItemId,
    String status,
    String errorCode,
    String message) {}
