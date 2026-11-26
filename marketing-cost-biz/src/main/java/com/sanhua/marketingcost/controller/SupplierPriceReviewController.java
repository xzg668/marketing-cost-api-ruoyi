package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotecosting.SupplierPriceConfirmationResponse;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewWorkflow;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/supplier-price-reviews")
public class SupplierPriceReviewController {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SupplierPriceReviewController.class);
  private final SupplierPriceReviewWorkflow workflow;
  private final SupplierPriceReviewExportService exports;
  public SupplierPriceReviewController(SupplierPriceReviewWorkflow workflow,
      SupplierPriceReviewExportService exports) {
    this.workflow = workflow;
    this.exports = exports;
  }

  @GetMapping("/export")
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public void export(@PathVariable String oaNo, @PathVariable Long itemId,
      @RequestParam String periodMonth, HttpServletResponse response) throws IOException {
    byte[] workbook = exports.export(oaNo, itemId, periodMonth);
    String filename = URLEncoder.encode("供货比率补齐_" + oaNo + "_" + itemId + "_" + periodMonth + ".xlsx",
        StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
    response.getOutputStream().write(workbook);
  }

  @PostMapping("/confirm")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<SupplierPriceConfirmationResponse> confirm(@PathVariable String oaNo,
      @PathVariable Long itemId, @RequestBody SupplierPriceReviewWorkflow.Confirmation request,
      Authentication authentication) {
    try {
      var decision = workflow.confirm(oaNo, itemId, request, authentication.getName());
      return CommonResult.success(workflow.repriceConfirmed(decision));
    } catch (IllegalArgumentException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }

  @PostMapping("/save")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<List<SupplierPriceReviewWorkflow.SavedReview>> save(@PathVariable String oaNo,
      @PathVariable Long itemId, @RequestBody List<SupplierPriceReviewWorkflow.Confirmation> requests,
      Authentication authentication) {
    try {
      return CommonResult.success(workflow.saveReviews(oaNo, itemId, requests, authentication.getName()));
    } catch (IllegalArgumentException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }

  @GetMapping("/pending-reprice")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<List<SupplierPriceReviewWorkflow.SavedReview>> pendingReprice(@PathVariable String oaNo,
      @PathVariable Long itemId, @RequestParam String periodMonth) {
    return CommonResult.success(workflow.pendingReprice(oaNo, itemId, periodMonth));
  }

  @PostMapping("/reprice-saved")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<SupplierPriceConfirmationResponse> repriceSaved(@PathVariable String oaNo,
      @PathVariable Long itemId, @RequestBody List<Long> decisions) {
    try {
      return CommonResult.success(workflow.repriceSaved(oaNo, itemId, decisions));
    } catch (org.springframework.security.access.AccessDeniedException denied) {
      throw denied;
    } catch (IllegalArgumentException | com.sanhua.marketingcost.service.ingest.QuoteIngestException
        | com.sanhua.marketingcost.service.EffectiveTechnicalDataException
        | com.sanhua.marketingcost.service.reprice.MonthlyQuotationControl.QuotationBlocked blocked) {
      return CommonResult.error(400, blocked.getMessage());
    } catch (RuntimeException failed) {
      log.warn("Saved supplier decisions could not complete repricing: oaNo={}, itemId={}", oaNo, itemId, failed);
      return CommonResult.error(500, "选择已保存，重新取价暂未完成，请重试");
    }
  }
}
