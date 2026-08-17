package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewDecisionRequest;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewDetailResponse;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewListResponse;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewSubmitRequest;
import org.springframework.web.bind.annotation.PostMapping;
import com.sanhua.marketingcost.service.collaboration.CollaborationDomainException;
import com.sanhua.marketingcost.service.collaboration.FinanceReviewApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collaboration/finance-reviews")
public class FinanceCollaborationReviewController {
  private final FinanceReviewApplicationService service;

  public FinanceCollaborationReviewController(FinanceReviewApplicationService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:read')")
  @GetMapping("/mine")
  public CommonResult<FinanceReviewListResponse> mine(
      @RequestParam(defaultValue = "false") boolean completed) {
    return execute(() -> service.mine(completed));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:read')")
  @GetMapping("/{reviewId}")
  public CommonResult<FinanceReviewDetailResponse> detail(@PathVariable Long reviewId) {
    return execute(() -> service.detail(reviewId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:read')")
  @GetMapping("/{reviewId}/items/{itemId}")
  public CommonResult<FinanceReviewDetailResponse.ItemDetail> item(
      @PathVariable Long reviewId, @PathVariable Long itemId) {
    return execute(() -> service.itemDetail(reviewId, itemId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:decide')")
  @PutMapping("/{reviewId}/items/{itemId}/decision")
  public CommonResult<FinanceReviewDetailResponse> decide(
      @PathVariable Long reviewId, @PathVariable Long itemId,
      @RequestBody FinanceReviewDecisionRequest request) {
    return execute(() -> service.decide(reviewId, itemId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:decide')")
  @PostMapping("/{reviewId}/reject")
  public CommonResult<FinanceReviewDetailResponse> reject(
      @PathVariable Long reviewId, @RequestBody FinanceReviewSubmitRequest request) {
    return execute(() -> service.reject(reviewId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:decide')")
  @PostMapping("/{reviewId}/approve")
  public CommonResult<FinanceReviewDetailResponse> approve(
      @PathVariable Long reviewId, @RequestBody FinanceReviewSubmitRequest request) {
    return execute(() -> service.approve(reviewId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:review:decide')")
  @PostMapping("/{reviewId}/retry-recheck")
  public CommonResult<FinanceReviewDetailResponse> retryRecheck(@PathVariable Long reviewId) {
    return execute(() -> service.retryRecheck(reviewId));
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try { return CommonResult.success(supplier.get()); }
    catch (CollaborationDomainException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case TASK_ASSIGNEE_MISMATCH -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case TASK_VERSION_CONFLICT -> 409;
        default -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }

  @FunctionalInterface private interface Supplier<T> { T get(); }
}
