package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteBomPackageStructurePageResponse(
    String referenceFinishedCode,
    String sourceTopProductCode,
    String periodMonth,
    Long packageReferenceId,
    boolean found,
    long total,
    List<PackageComponentStructureLineDto> list,
    List<String> gaps) {}
