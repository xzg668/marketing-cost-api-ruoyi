package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;

public interface CostRunTaskExecutor {

  CostRunTaskScene scene();

  CostRunTaskExecutionResult execute(CostRunTask task, String workerId);
}
