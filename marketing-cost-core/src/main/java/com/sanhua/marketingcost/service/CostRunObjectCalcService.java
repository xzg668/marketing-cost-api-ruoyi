package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;

public interface CostRunObjectCalcService {

  CostRunObjectResult calculate(CostRunContext context);
}
