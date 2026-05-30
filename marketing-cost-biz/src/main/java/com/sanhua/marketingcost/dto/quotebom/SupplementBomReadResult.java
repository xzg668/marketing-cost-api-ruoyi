package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDate;
import java.util.List;

public record SupplementBomReadResult(
    String quoteProductCode,
    String productType,
    String supplementScope,
    String periodMonth,
    boolean found,
    Long supplementVersionId,
    Long taskId,
    String taskNo,
    String bomSource,
    LocalDate reuseValidUntil,
    List<QuoteBomSourceLineDto> lines,
    String gapMessage) {}
