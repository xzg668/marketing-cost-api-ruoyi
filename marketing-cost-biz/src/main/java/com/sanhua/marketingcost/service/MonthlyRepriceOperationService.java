package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;

public interface MonthlyRepriceOperationService {

  /** 取消未确认的月度调价批次。 */
  MonthlyRepriceProgressSnapshot cancel(String repriceNo, String operator);

  /** 将失败任务重新置为待执行，交由 Worker 再次领取。 */
  MonthlyRepriceProgressSnapshot retryFailed(String repriceNo, String operator);
}
