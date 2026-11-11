package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunTaskSubmissionResult;
import java.util.List;

/** 通用成本核算任务提交服务。 */
public interface CostRunTaskSubmissionService {

  /** 只登记产品任务，资料及已有成本修订由后台逐品核验。 */
  CostRunTaskSubmissionResult submitQuoteForPreparation(
      String oaNo, String pricingMonth, String submittedBy);

  CostRunTaskSubmissionResult submitQuote(String oaNo);

  CostRunTaskSubmissionResult submitQuote(String oaNo, List<Long> oaFormItemIds);

  CostRunTaskSubmissionResult submitQuote(
      String oaNo, List<Long> oaFormItemIds, String pricingMonth, String submittedBy);

}
