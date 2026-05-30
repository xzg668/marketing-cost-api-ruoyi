package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;

public interface MonthlyRepriceProgressService {

  /** 重新统计任务状态并在计算完成时收口批次状态。 */
  MonthlyRepriceProgressSnapshot refreshProgress(String repriceNo);

  /** 查询当前批次进度，不修改批次。 */
  MonthlyRepriceProgressSnapshot getProgress(String repriceNo);

  /** 查询同月份、同业务单元下最新已确认批次。 */
  MonthlyRepriceBatch getLatestConfirmedBatch(String pricingMonth, String businessUnitType);
}
