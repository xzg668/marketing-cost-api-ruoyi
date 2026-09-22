package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataProfileResponse(
    Long versionId, Integer versionNo, String versionStatus,
    String productModel, String productProperty, Boolean sourceFirstQuote,
    Boolean hasAdditionalFees, String unitToolingFee, String unitMouldFee, String unitCertificationFee,
    Integer expectedVersion, Integer draftRowVersion, LocalDateTime updatedAt) {}
