package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.entity.CostRunTask;

public interface MonthlyRepriceCostRunAdapter {

  CostRunObjectResult execute(CostRunTask task, String workerId);
}
