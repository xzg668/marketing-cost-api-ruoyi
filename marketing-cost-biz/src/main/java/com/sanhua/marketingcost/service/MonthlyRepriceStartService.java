package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;

public interface MonthlyRepriceStartService {

  /** 发起月度调价：创建批次并立即展开待计算任务。 */
  MonthlyRepriceBatchCreateResponse start(
      MonthlyRepriceBatchCreateRequest request, String operator);
}
