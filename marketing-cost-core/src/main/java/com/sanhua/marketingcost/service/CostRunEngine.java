package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;

public interface CostRunEngine {

  CostRunObjectResult run(CostRunContext context);
}
