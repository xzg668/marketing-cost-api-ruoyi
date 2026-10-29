package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;

public record SupplierSupplyRatioChange(long id, Long previousVersionId, long versionId,
    String materialCode, String supplierCode, String materialName, String supplierName,
    String importFileName, String batchNo, String changedByName, LocalDateTime changedAt,
    String changeReason, String beforeValue, String afterValue) {}
