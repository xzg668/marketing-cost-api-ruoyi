package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataPriceResponse(Long taskId, Long productId, Integer expectedVersion,
    Long versionId, String versionStatus, String moduleStatus, boolean editable, boolean historical,
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = TechnicalDataPriceContentSerializer.class)
    TechnicalDataSupplementContent.Prices content, TechnicalDataPriceRequirementsResponse requirements,
    List<TechnicalDataPriceOwner> owners, List<String> issues,
    List<TechnicalDataPricePublicationStatus> publications) {}
