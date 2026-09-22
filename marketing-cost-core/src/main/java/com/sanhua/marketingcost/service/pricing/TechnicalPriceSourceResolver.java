package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;

/** 核算 core 读取经技术审批发布的来源；审批归属和公式执行由业务模块负责。 */
public interface TechnicalPriceSourceResolver {
  PriceResolveResult resolve(CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context);
}
