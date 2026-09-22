package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SupplementSnapshot;

public record TechnicalDataProductResponse(
    Long id,
    Long oaFormItemId,
    Integer levelNo,
    String materialNo,
    String productName,
    String sourceModel,
    String sourceSpec,
    java.math.BigDecimal annualVolume,
    String annualVolumeUnit,
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
    List<TechnicalDataModuleResponse> modules,
    Integer contentSchemaVersion,
    SupplementSnapshot supplementContent) {}
