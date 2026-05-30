package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record PackageComponentStructureReadResult(
    String referenceFinishedCode,
    String sourceTopProductCode,
    String periodMonth,
    Long packageReferenceId,
    boolean found,
    List<PackageComponentStructureLineDto> lines,
    List<String> gaps) {}
