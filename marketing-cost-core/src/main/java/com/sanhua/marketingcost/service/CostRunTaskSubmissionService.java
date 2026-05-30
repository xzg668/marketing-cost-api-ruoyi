package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;

/** 通用成本核算任务提交服务。 */
public interface CostRunTaskSubmissionService {

  CostRunTaskSubmissionResult submitQuote(String oaNo);

  CostRunTaskSubmissionResult submitMonthlyReprice(CostRunMonthlyRepriceSubmitRequest request);
}
