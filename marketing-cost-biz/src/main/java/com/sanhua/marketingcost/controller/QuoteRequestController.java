package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-requests")
public class QuoteRequestController {
  private final QuoteRequestQueryService quoteRequestQueryService;

  public QuoteRequestController(QuoteRequestQueryService quoteRequestQueryService) {
    this.quoteRequestQueryService = quoteRequestQueryService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping
  public CommonResult<PageResult<QuoteRequestListItemResponse>> page(
      @RequestParam(value = "pageNo", required = false) Integer pageNo,
      @RequestParam(value = "pageSize", required = false) Integer pageSize,
      @RequestParam(value = "oaNo", required = false) String oaNo,
      @RequestParam(value = "processCode", required = false) String processCode,
      @RequestParam(value = "classificationStatus", required = false) String classificationStatus) {
    return CommonResult.success(
        quoteRequestQueryService.pageRequests(
            pageNo, pageSize, oaNo, processCode, classificationStatus));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}")
  public CommonResult<QuoteRequestDetailResponse> detail(@PathVariable("oaNo") String oaNo) {
    try {
      return CommonResult.success(quoteRequestQueryService.getRequestDetail(oaNo));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:confirm')")
  @PostMapping("/{oaNo}/confirm-classification")
  public CommonResult<QuoteRequestDetailResponse> confirmClassification(
      @PathVariable("oaNo") String oaNo,
      @RequestBody QuoteRequestConfirmClassificationRequest request) {
    try {
      return CommonResult.success(quoteRequestQueryService.confirmClassification(oaNo, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
