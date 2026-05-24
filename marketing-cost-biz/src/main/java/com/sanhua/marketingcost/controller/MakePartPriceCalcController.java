package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MakePartPriceCalcPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGapPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.MakePartPriceCalcService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/make-part-price-calc")
public class MakePartPriceCalcController {

  private static final String CONTENT_TYPE_XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final MakePartPriceCalcService service;

  public MakePartPriceCalcController(MakePartPriceCalcService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  @GetMapping("/page")
  public CommonResult<MakePartPriceCalcPageResponse> page(
      @ModelAttribute MakePartPriceCalcQueryRequest request) {
    return CommonResult.success(service.page(request));
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  @GetMapping("/gap-page")
  public CommonResult<MakePartPriceGapPageResponse> gapPage(
      @ModelAttribute MakePartPriceCalcQueryRequest request) {
    return CommonResult.success(service.gapPage(request));
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:generate')")
  @PostMapping("/generate")
  public CommonResult<MakePartPriceGenerateResponse> generate(
      @RequestBody MakePartPriceGenerateRequest request) {
    return CommonResult.success(service.generate(request));
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  @GetMapping("/latest-batch")
  public CommonResult<String> latestBatch(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String parentMaterialNo) {
    return CommonResult.success(service.latestBatch(oaNo, businessUnitType, parentMaterialNo));
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:export')")
  @GetMapping("/export")
  public void export(
      @ModelAttribute MakePartPriceCalcQueryRequest request,
      HttpServletResponse response)
      throws IOException {
    String fileName = "制造件价格生成_" + System.currentTimeMillis() + ".xlsx";
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType(CONTENT_TYPE_XLSX);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    service.export(request, response.getOutputStream());
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  @GetMapping("/status-summary")
  public CommonResult<Map<String, Integer>> statusSummary(
      @ModelAttribute MakePartPriceCalcQueryRequest request) {
    return CommonResult.success(service.statusSummary(request));
  }

  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  @GetMapping("/{id:\\d+}")
  public CommonResult<MakePartPriceCalcRow> detail(@PathVariable Long id) {
    MakePartPriceCalcRow row = service.get(id);
    if (row == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "make part calc row not found");
    }
    return CommonResult.success(row);
  }
}
