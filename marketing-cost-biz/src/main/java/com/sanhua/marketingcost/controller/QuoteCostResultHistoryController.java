package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteMonthlyCostResultDetailResponse;
import com.sanhua.marketingcost.service.ingest.QuoteCostResultHistoryService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 报价详情页使用的只读成本结果历史入口。 */
@RestController
@RequestMapping("/api/v1/quote-requests")
public class QuoteCostResultHistoryController {
  private final QuoteCostResultHistoryService historyService;

  public QuoteCostResultHistoryController(QuoteCostResultHistoryService historyService) {
    this.historyService = historyService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-result-history")
  public CommonResult<QuoteCostResultHistoryResponse> history(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(historyService.listHistory(oaNo, oaFormItemId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-result-history/monthly/{resultId}")
  public CommonResult<QuoteMonthlyCostResultDetailResponse> monthlyResult(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("resultId") Long resultId) {
    try {
      return CommonResult.success(
          historyService.getMonthlyResult(oaNo, oaFormItemId, resultId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
