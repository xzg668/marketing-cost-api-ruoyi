package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunMonthlyRepriceSubmitRequest;
import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import java.util.List;

/** 通用成本核算任务提交服务。 */
public interface CostRunTaskSubmissionService {

  CostRunTaskSubmissionResult submitQuote(String oaNo);

  CostRunTaskSubmissionResult submitQuote(String oaNo, List<Long> oaFormItemIds);

  CostRunTaskSubmissionResult submitMonthlyReprice(CostRunMonthlyRepriceSubmitRequest request);
}
