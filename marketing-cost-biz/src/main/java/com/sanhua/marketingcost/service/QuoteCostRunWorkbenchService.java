package com.sanhua.marketingcost.service;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
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

  QuoteCostRunWorkbenchResponse trial(
      String oaNo, Long oaFormItemId, QuoteCostRunTrialRequest request);

  QuoteCostRunSummaryResponse confirm(
      String oaNo,
      Long oaFormItemId,
      String costRunNo,
      QuoteCostRunConfirmRequest request);

  int exportVersion(String oaNo, Long oaFormItemId, Long versionId, OutputStream output)
      throws IOException;
}
