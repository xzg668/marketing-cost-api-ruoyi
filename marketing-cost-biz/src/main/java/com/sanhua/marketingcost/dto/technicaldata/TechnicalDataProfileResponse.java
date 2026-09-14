package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;

public record TechnicalDataProfileResponse(
    Long versionId,
    Integer versionNo,
    String versionStatus,
    String productModel,
    String productProperty,
    Boolean newProduct,
    Integer expectedVersion,
    Integer draftRowVersion,
    LocalDateTime updatedAt) {}
