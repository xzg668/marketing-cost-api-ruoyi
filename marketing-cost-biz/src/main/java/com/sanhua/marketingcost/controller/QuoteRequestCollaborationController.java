package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationHistoryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationSummaryResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteTechnicianCandidatesResponse;
import com.sanhua.marketingcost.service.collaboration.CollaborationDomainException;
import com.sanhua.marketingcost.service.collaboration.QuoteItemCollaborationProjectionService;
import com.sanhua.marketingcost.service.collaboration.QuoteRequestCollaborationApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}")
public class QuoteRequestCollaborationController {
  private final QuoteItemCollaborationProjectionService projectionService;
  private final QuoteRequestCollaborationApplicationService applicationService;

  public QuoteRequestCollaborationController(
      QuoteItemCollaborationProjectionService projectionService,
      QuoteRequestCollaborationApplicationService applicationService) {
    this.projectionService = projectionService;
    this.applicationService = applicationService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/collaboration-summary")
  public CommonResult<QuoteCollaborationSummaryResponse> summary(@PathVariable String oaNo) {
    return execute(() -> projectionService.summary(oaNo));
  }

  @PostMapping("/collaboration-summary/refresh")
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  public CommonResult<QuoteCollaborationSummaryResponse> refreshSummary(
      @PathVariable String oaNo) {
    return execute(() -> projectionService.refreshSummary(oaNo));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:bom-check')")
  @PostMapping("/items/{itemId}/collaboration/scan")
  public CommonResult<QuoteItemCollaborationResponse> scan(
      @PathVariable String oaNo, @PathVariable Long itemId) {
    return execute(() -> projectionService.project(oaNo, itemId));
  }

  @PreAuthorize("@ss.hasAnyPermi('collaboration:task:create')")
  @GetMapping("/items/{itemId}/collaboration/technician-candidates")
  public CommonResult<QuoteTechnicianCandidatesResponse> technicianCandidates(
      @PathVariable String oaNo, @PathVariable Long itemId) {
    return execute(() -> applicationService.technicianCandidates(oaNo, itemId));
  }

  @PreAuthorize("@ss.hasAnyPermi('collaboration:task:create')")
  @PostMapping("/items/{itemId}/collaboration/start")
  public CommonResult<QuoteCollaborationStartResponse> start(
      @PathVariable String oaNo,
      @PathVariable Long itemId,
      @RequestBody(required = false) QuoteCollaborationStartRequest request) {
    return execute(() -> applicationService.start(oaNo, itemId, request));
  }

  @PreAuthorize("@ss.hasAnyPermi('collaboration:task:create')")
  @PostMapping("/collaboration/batch-start")
  public CommonResult<QuoteCollaborationBatchStartResponse> batchStart(
      @PathVariable String oaNo,
      @RequestBody QuoteCollaborationBatchStartRequest request) {
    return execute(() -> applicationService.batchStart(oaNo, request));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/items/{itemId}/collaboration/history")
  public CommonResult<QuoteCollaborationHistoryResponse> history(
      @PathVariable String oaNo, @PathVariable Long itemId) {
    return execute(() -> projectionService.history(oaNo, itemId));
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (CollaborationDomainException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }

  @FunctionalInterface
  private interface Supplier<T> { T get(); }
}
