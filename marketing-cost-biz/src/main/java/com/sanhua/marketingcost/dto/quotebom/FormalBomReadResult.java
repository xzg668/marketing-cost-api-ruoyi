package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record FormalBomReadResult(
    String productCode,
    String periodMonth,
    String bomPurpose,
    boolean found,
    List<QuoteBomSourceLineDto> lines,
    String gapMessage) {}
