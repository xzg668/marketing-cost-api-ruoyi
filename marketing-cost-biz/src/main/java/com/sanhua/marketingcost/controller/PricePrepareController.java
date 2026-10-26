package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapRevokeRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cost/price-prepare")
public class PricePrepareController {

  private final PricePrepareQueryService queryService;
  private final MakePartNoScrapConfirmationService noScrapConfirmationService;

  public PricePrepareController(
      PricePrepareQueryService queryService,
      MakePartNoScrapConfirmationService noScrapConfirmationService) {
    this.queryService = queryService;
    this.noScrapConfirmationService = noScrapConfirmationService;
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:generate')")
  @PostMapping("/generate")
  public CommonResult<PricePrepareGenerateResult> generate(
      @RequestBody PricePrepareGenerateRequest request) {
    return quotationWorkbenchRequired();
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:generate')")
  @PostMapping("/generate-bulk")
  public CommonResult<PricePrepareBulkGenerateResponse> generateBulk(
      @RequestBody PricePrepareBulkGenerateRequest request) {
    return quotationWorkbenchRequired();
  }

  private <T> CommonResult<T> quotationWorkbenchRequired() {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
        "请从报价单的单产品核算工作台准备价格，以当前产品有效 BOM 确定月价范围");
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')"
      + " and (#request == null or #request.ownerScope == null"
      + " or !#request.ownerScope.equalsIgnoreCase('ALL')"
      + " or @ss.hasPermi('cost:price-prepare:list-all'))")
  @GetMapping("/candidates")
  public CommonResult<PricePrepareCandidatePageResponse> candidates(
      @ModelAttribute PricePrepareCandidateQueryRequest request) {
    return CommonResult.success(queryService.pageCandidates(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/oa-summary")
  public CommonResult<PricePrepareOaSummaryPageResponse> oaSummary(
      @ModelAttribute PricePrepareOaSummaryQueryRequest request) {
    return CommonResult.success(queryService.pageOaSummaries(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/top-product-summary")
  public CommonResult<PricePrepareTopProductSummaryPageResponse> topProductSummary(
      @ModelAttribute PricePrepareTopProductSummaryQueryRequest request) {
    return CommonResult.success(queryService.pageTopProductSummaries(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/batches")
  public CommonResult<PricePrepareBatchPageResponse> batches(
      @ModelAttribute PricePrepareBatchQueryRequest request) {
    return CommonResult.success(queryService.pageBatches(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:detail')")
  @GetMapping("/items")
  public CommonResult<PricePrepareItemPageResponse> items(
      @ModelAttribute PricePrepareItemQueryRequest request) {
    return CommonResult.success(queryService.pageItems(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:gap')")
  @GetMapping("/gaps")
  public CommonResult<PricePrepareGapPageResponse> gaps(
      @ModelAttribute PricePrepareGapQueryRequest request) {
    return CommonResult.success(queryService.pageGaps(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:no-scrap-confirm')")
  @PostMapping("/no-scrap-confirmations")
  public CommonResult<NoScrapConfirmResponse> confirmNoScrap(
      @RequestBody NoScrapConfirmRequest request, Authentication authentication) {
    try {
      return CommonResult.success(
          noScrapConfirmationService.confirm(request, currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:no-scrap-revoke')")
  @PostMapping("/no-scrap-confirmations/{id}/revoke")
  public CommonResult<NoScrapConfirmResponse> revokeNoScrap(
      @PathVariable Long id,
      @RequestBody(required = false) NoScrapRevokeRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(
          noScrapConfirmationService.revoke(id, request, currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:gap')")
  @GetMapping("/no-scrap-confirmations")
  public CommonResult<NoScrapConfirmationPageResponse> noScrapConfirmations(
      @ModelAttribute NoScrapConfirmationPageRequest request) {
    return CommonResult.success(noScrapConfirmationService.page(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:gap')")
  @GetMapping("/no-scrap-confirmations/effective")
  public CommonResult<NoScrapConfirmResponse> effectiveNoScrapConfirmation(
      @RequestParam String materialNo,
      @RequestParam String periodMonth,
      @RequestParam(required = false) String businessUnitType) {
    try {
      return CommonResult.success(
          noScrapConfirmationService.findEffective(materialNo, periodMonth, businessUnitType));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }

  private <T> CommonResult<T> badRequest(IllegalArgumentException ex) {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
  }

}
