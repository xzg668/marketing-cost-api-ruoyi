package com.sanhua.marketingcost.controller;

import com.sanhua.marketingcost.service.costing.QuoteCostingGapExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/costing-gaps")
public class QuoteCostingGapExportController {
  private final QuoteCostingGapExportService exports;
  public QuoteCostingGapExportController(QuoteCostingGapExportService exports) { this.exports = exports; }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public cn.iocoder.yudao.framework.common.pojo.CommonResult<QuoteCostingGapExportService.Details> details(
      @PathVariable String oaNo, @RequestParam String category) throws IOException {
    return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(
        exports.details(oaNo, QuoteCostingGapExportService.Category.valueOf(category)));
  }

  @GetMapping("/export")
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public void export(@PathVariable String oaNo, @RequestParam String category,
      HttpServletResponse response) throws IOException {
    var kind = QuoteCostingGapExportService.Category.valueOf(category);
    byte[] bytes = exports.export(oaNo, kind);
    String filename = URLEncoder.encode(kind.label + "_" + oaNo + ".xlsx", StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
    response.getOutputStream().write(bytes);
  }
}
