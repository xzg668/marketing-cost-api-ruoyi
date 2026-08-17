package com.sanhua.marketingcost.dto.collaboration;

public record TechnicalPackagePriceCheckResponse(
    boolean passed,
    String status,
    String message,
    Integer taskVersion,
    int checkedMaterialCount,
    int priceGapCount,
    TechnicalPackageWorkspaceResponse workspace) {}
