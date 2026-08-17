package com.sanhua.marketingcost.dto.collaboration;

public record TechnicalPackageCopyRequest(
    Integer expectedTaskVersion,
    String sourceMode,
    Long sourceId) {}
