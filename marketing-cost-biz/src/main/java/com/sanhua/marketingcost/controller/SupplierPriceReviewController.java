package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewWorkflow;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/supplier-price-reviews")
public class SupplierPriceReviewController {
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
