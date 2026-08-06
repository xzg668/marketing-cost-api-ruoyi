package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionHistoryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import java.util.List;

/** 报价物料明细标准/替代选择 API 的应用层入口。 */
public interface QuoteBomAlternativeApplicationService {

  QuoteBomAlternativeSummaryResponse getAlternativeGroups(
      String oaNo, Long oaFormItemId, String periodMonth);

  QuoteBomAlternativeSelectionResponse saveSelection(
      String oaNo,
      Long oaFormItemId,
      String alternativeGroupKey,
      QuoteBomAlternativeSelectionRequest request,
      String operator);

  List<QuoteBomAlternativeSelectionHistoryResponse> getSelectionHistory(
      String oaNo,
      Long oaFormItemId,
      String alternativeGroupKey,
      String periodMonth);
}
