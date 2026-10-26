package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataProfileResponse(
    Long versionId, Integer versionNo, String versionStatus,
    String productModel, String productProperty, Boolean sourceFirstQuote,
    Boolean hasAdditionalFees, String unitToolingFee, String unitMouldFee, String unitCertificationFee,
    String unitDepreciationFee, Integer feeEncodingVersion,
    Integer expectedVersion, Integer draftRowVersion, LocalDateTime updatedAt,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    TechnicalDataPlateMaterialResponse plateMaterial) {}
