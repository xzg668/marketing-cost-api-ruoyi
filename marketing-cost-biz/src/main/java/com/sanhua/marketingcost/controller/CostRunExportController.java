package com.sanhua.marketingcost.controller;

import com.sanhua.marketingcost.service.JjbExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 见机表 Excel 导出接口 (Task #10) —— 财务对账武器。
 *
 * <p>路径：{@code GET /api/v1/cost-run/{oaNo}/export-jjb?productCode=xxx}
 *
 * <p>响应：xlsx 二进制流；文件名编码 UTF-8 兼容浏览器下载（filename* RFC 5987）。
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunExportController {

  private static final Logger log = LoggerFactory.getLogger(CostRunExportController.class);
  private static final String CONTENT_TYPE_XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final JjbExportService jjbExportService;

  public CostRunExportController(JjbExportService jjbExportService) {
    this.jjbExportService = jjbExportService;
  }

  @PreAuthorize("@ss.hasPermi('cost:run:export')")
  @GetMapping("/{oaNo}/export-jjb")
  public void exportJjb(
      @PathVariable("oaNo") String oaNo,
      @RequestParam("productCode") String productCode,
      HttpServletResponse response)
      throws IOException {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, "oaNo / productCode required");
      return;
    }
    String fileName = "JJB_" + oaNo.trim() + "_" + productCode.trim() + ".xlsx";
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType(CONTENT_TYPE_XLSX);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);

    int rows = jjbExportService.export(oaNo, productCode, response.getOutputStream());
    log.info("见机表导出 oaNo={} productCode={} 部品行数={}", oaNo, productCode, rows);
  }
}
