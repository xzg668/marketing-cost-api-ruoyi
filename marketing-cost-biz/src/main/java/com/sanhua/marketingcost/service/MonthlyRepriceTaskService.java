package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.util.List;

public interface MonthlyRepriceTaskService {

  /** 将候选核算对象转成 lp_cost_run_task 的 MONTHLY_REPRICE 任务。 */
  MonthlyRepriceObjectExpandResult createTasks(
      MonthlyRepriceBatch batch, List<MonthlyRepriceCalcObject> calcObjects);
}
