package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;

/** 一次取价的明确输入，数量与组织保留在各项中，集合准备不能按料号吞并它们。 */
public record PriceResolveRequest(
    CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {}
