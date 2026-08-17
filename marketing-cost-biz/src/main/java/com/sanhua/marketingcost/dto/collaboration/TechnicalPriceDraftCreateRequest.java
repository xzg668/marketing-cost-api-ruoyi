package com.sanhua.marketingcost.dto.collaboration;

public record TechnicalPriceDraftCreateRequest(
    String priceType,
    String referenceSourceType,
    Long referenceSourceId) {}
