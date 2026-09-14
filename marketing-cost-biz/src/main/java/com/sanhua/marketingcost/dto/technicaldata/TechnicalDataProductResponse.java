package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataProductResponse(
    Long id,
    Long oaFormItemId,
    Integer levelNo,
    String materialNo,
    String productName,
    String sourceModel,
    String sourceSpec,
    String quoteNo,
    String accountingMonth,
    String sourceSnapshotJson,
    String sourceFingerprint,
    String productStatus,
    Long currentEditVersionId,
    Long latestSubmittedVersionId,
    Long effectiveVersionId,
    Integer rowVersion,
    TechnicalDataProfileResponse profile,
    List<TechnicalDataModuleResponse> modules) {}
