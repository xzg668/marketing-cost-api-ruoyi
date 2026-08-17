package com.sanhua.marketingcost.dto.collaboration;

public record TechnicalPriceDraftChangeReferenceRequest(
    Integer expectedVersion,
    String referenceSourceType,
    Long referenceSourceId) {}
