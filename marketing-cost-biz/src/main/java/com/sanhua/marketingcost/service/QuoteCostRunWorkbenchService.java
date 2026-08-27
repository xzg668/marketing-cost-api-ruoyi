package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import java.io.IOException;
import java.io.OutputStream;

public interface QuoteCostRunWorkbenchService {

  QuoteCostRunWorkbenchResponse getCostRun(String oaNo, Long oaFormItemId, String periodMonth);

  QuoteCostRunWorkbenchResponse getCostRun(
      String oaNo, Long oaFormItemId, String periodMonth, Long versionId);

  /** 统一流水线专用：在一个事务内完成成本计算、底稿写入和当前成功版本切换。 */
  QuoteCostRunWorkbenchResponse runToSuccess(
      String oaNo,
      Long oaFormItemId,
      QuoteCostRunTrialRequest request,
      String completedBy);

  int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException;
}
