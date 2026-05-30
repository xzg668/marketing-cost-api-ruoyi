package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteBomCostingProductPageResponse(
    long total,
    List<QuoteBomCostingProductDto> list) {}
