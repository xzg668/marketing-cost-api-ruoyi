package com.sanhua.marketingcost.service;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCuMaterialDifferenceResponse;
import java.io.IOException;
import java.io.OutputStream;

public interface QuoteCostRunWorkbenchService {

  QuoteCostRunWorkbenchResponse getCostRun(String oaNo, Long oaFormItemId, String periodMonth);

  QuoteCostRunWorkbenchResponse getCostRun(
      String oaNo, Long oaFormItemId, String periodMonth, Long versionId);

  PageResult<QuoteCuMaterialDifferenceResponse> pageCuMaterialDifferences(
      String oaNo,
      Long oaFormItemId,
      String costRunNo,
      Integer pageNo,
      Integer pageSize,
      String parentMaterialCode,
      String materialCode,
      Boolean onlyDifferent,
      String differenceSign);

  /** 统一流水线专用：在一个事务内完成成本计算、底稿写入和当前成功版本切换。 */
  QuoteCostRunWorkbenchResponse runToSuccess(
      String oaNo,
      Long oaFormItemId,
      QuoteCostRunTrialRequest request,
      String completedBy);

  int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException;
}
