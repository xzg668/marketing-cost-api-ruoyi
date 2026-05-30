package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteBomCostingRowPageResponse(
    long total,
    List<QuoteBomCostingRowDto> list) {}
