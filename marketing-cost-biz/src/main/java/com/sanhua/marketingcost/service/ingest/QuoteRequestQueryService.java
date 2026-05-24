package com.sanhua.marketingcost.service.ingest;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogListItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;

public interface QuoteRequestQueryService {
  PageResult<QuoteRequestListItemResponse> pageRequests(
      Integer pageNo,
      Integer pageSize,
      String oaNo,
      String processCode,
      String sourceType,
      String classificationStatus);

  QuoteRequestDetailResponse getRequestDetail(String oaNo);

  QuoteRequestDetailResponse confirmClassification(
      String oaNo, QuoteRequestConfirmClassificationRequest request);

  PageResult<QuoteIngestLogListItemResponse> pageLogs(
      Integer pageNo, Integer pageSize, String oaNo, String sourceType, String ingestStatus);

  QuoteIngestLogDetailResponse getLogDetail(Long id);
}
