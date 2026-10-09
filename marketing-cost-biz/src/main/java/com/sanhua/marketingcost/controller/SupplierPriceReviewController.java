package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewWorkflow;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/supplier-price-reviews")
public class SupplierPriceReviewController {
  private final SupplierPriceReviewWorkflow workflow;
  public SupplierPriceReviewController(SupplierPriceReviewWorkflow workflow) { this.workflow = workflow; }

  @PostMapping("/confirm")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<QuotePricePrepareWorkbenchResponse> confirm(@PathVariable String oaNo,
      @PathVariable Long itemId, @RequestBody SupplierPriceReviewWorkflow.Confirmation request,
      Authentication authentication) {
    try {
      var decision = workflow.confirm(oaNo, itemId, request, authentication.getName());
      return CommonResult.success(workflow.reprice(decision));
    } catch (IllegalArgumentException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }
}
