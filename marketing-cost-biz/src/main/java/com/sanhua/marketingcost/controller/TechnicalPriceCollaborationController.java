package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.FormalPriceReferenceSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftChangeReferenceRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftCreateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftSaveRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceDraftValidateRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPriceGapWorkspaceResponse;
import com.sanhua.marketingcost.service.collaboration.CollaborationDomainException;
import com.sanhua.marketingcost.service.collaboration.CollaborationOptimisticLockException;
import com.sanhua.marketingcost.service.collaboration.TechnicalPriceDraftApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collaboration")
public class TechnicalPriceCollaborationController {
  private final TechnicalPriceDraftApplicationService service;

  public TechnicalPriceCollaborationController(TechnicalPriceDraftApplicationService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/product-tasks/{taskId}/price-gaps")
  public CommonResult<TechnicalPriceGapWorkspaceResponse> workspace(@PathVariable Long taskId) {
    return execute(() -> service.workspace(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/price-gaps/{gapId}/formal-prices")
  public CommonResult<FormalPriceReferenceSearchResponse> formalPrices(
      @PathVariable Long gapId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String priceType) {
    return execute(() -> service.search(gapId, keyword, priceType));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/price-gaps/{gapId}/draft/copy")
  public CommonResult<TechnicalPriceDraftResponse> copy(
      @PathVariable Long gapId, @RequestBody TechnicalPriceDraftCreateRequest request) {
    return execute(() -> service.create(gapId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/price-gaps/{gapId}/draft/direct")
  public CommonResult<TechnicalPriceDraftResponse> direct(
      @PathVariable Long gapId, @RequestBody TechnicalPriceDraftCreateRequest request) {
    return execute(() -> service.create(gapId,
        new TechnicalPriceDraftCreateRequest(request == null ? null : request.priceType(), null, null)));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/price-drafts/{draftId}")
  public CommonResult<TechnicalPriceDraftResponse> draft(@PathVariable Long draftId) {
    return execute(() -> service.detail(draftId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PutMapping("/price-drafts/{draftId}")
  public CommonResult<TechnicalPriceDraftResponse> save(
      @PathVariable Long draftId, @RequestBody TechnicalPriceDraftSaveRequest request) {
    return execute(() -> service.save(draftId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/price-drafts/{draftId}/validate")
  public CommonResult<TechnicalPriceDraftResponse> validate(
      @PathVariable Long draftId, @RequestBody TechnicalPriceDraftValidateRequest request) {
    return execute(() -> service.validate(draftId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/price-drafts/{draftId}/change-reference")
  public CommonResult<TechnicalPriceDraftResponse> changeReference(
      @PathVariable Long draftId,
      @RequestBody TechnicalPriceDraftChangeReferenceRequest request) {
    return execute(() -> service.changeReference(draftId,
        request == null ? null : request.expectedVersion(),
        request == null ? null : new TechnicalPriceDraftCreateRequest(
            null, request.referenceSourceType(), request.referenceSourceId())));
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (CollaborationDomainException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case TASK_ASSIGNEE_MISMATCH -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case TASK_VERSION_CONFLICT, IDEMPOTENCY_CONFLICT -> 409;
        default -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (CollaborationOptimisticLockException exception) {
      return CommonResult.error(409, exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }

  @FunctionalInterface
  private interface Supplier<T> { T get(); }
}
