package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorAdjustBatchDetailDto;
import com.sanhua.marketingcost.dto.FactorAdjustBatchPageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustImportRequest;
import com.sanhua.marketingcost.dto.FactorAdjustImportResponse;
import com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest;
import com.sanhua.marketingcost.dto.FactorAdjustPricePageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListPageResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest;
import com.sanhua.marketingcost.service.FactorAdjustImportService;
import com.sanhua.marketingcost.service.FactorAdjustQueryService;
import com.sanhua.marketingcost.service.FactorAdjustTemplateExportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/price-linked/factor-adjust")
public class FactorAdjustController {

  private static final String CONTENT_TYPE_XLSX =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final FactorAdjustTemplateExportService templateExportService;
  private final FactorAdjustQueryService queryService;
  private final FactorAdjustImportService importService;

  public FactorAdjustController(
      FactorAdjustTemplateExportService templateExportService,
      FactorAdjustQueryService queryService,
      FactorAdjustImportService importService) {
    this.templateExportService = templateExportService;
    this.queryService = queryService;
    this.importService = importService;
  }

  /** 导入影响因素月度调价 Excel，只处理影响因素价格，不改变联动公式和变量绑定。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:import') or @ss.hasPermi('price:finance-base:edit')")
  @PostMapping("/import")
  public CommonResult<FactorAdjustImportResponse> importAdjustExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam("businessUnitType") String businessUnitType,
      @RequestParam(required = false, defaultValue = "NORMAL") String adjustType,
      @RequestParam("usageScope") String usageScope,
      @RequestParam(required = false) String remark,
      Authentication authentication) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    FactorAdjustImportRequest request = new FactorAdjustImportRequest();
    request.setPricingMonth(pricingMonth);
    request.setBusinessUnitType(businessUnitType);
    request.setAdjustType(adjustType);
    request.setUsageScope(usageScope);
    request.setRemark(remark);
    try {
      return CommonResult.success(
          importService.importAdjustExcel(
              file.getInputStream(), file.getOriginalFilename(), request,
              currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    } catch (IOException ex) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + ex.getMessage());
    }
  }

  /** 查询月度调价批次。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:list')"
      + " or @ss.hasPermi('price:finance-base:batch:list')"
      + " or @ss.hasPermi('price:finance-base:list')")
  @GetMapping("/batches")
  public CommonResult<FactorAdjustBatchPageResponse> listBatches(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String adjustBatchNo,
      @RequestParam(required = false) String adjustType,
      @RequestParam(required = false) String usageScope,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String uploadedBy,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAllUploaders,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    FactorAdjustBatchQueryRequest request = new FactorAdjustBatchQueryRequest();
    request.setPricingMonth(pricingMonth);
    request.setBusinessUnitType(businessUnitType);
    request.setAdjustBatchNo(adjustBatchNo);
    request.setAdjustType(adjustType);
    request.setUsageScope(usageScope);
    request.setStatus(status);
    request.setUploadedBy(uploadedBy);
    request.setIncludeAllUploaders(includeAllUploaders);
    request.setLimit(limit);
    request.setPage(page);
    request.setPageSize(pageSize);
    return CommonResult.success(queryService.pageBatches(request));
  }

  /** 查询某个月度调价批次详情。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:detail')"
      + " or @ss.hasPermi('price:factor-adjust:list')"
      + " or @ss.hasPermi('price:finance-base:batch:list')"
      + " or @ss.hasPermi('price:finance-base:list')")
  @GetMapping("/batches/{id}")
  public CommonResult<FactorAdjustBatchDetailDto> getBatch(@PathVariable Long id) {
    FactorAdjustBatchDetailDto detail = queryService.getBatchDetail(id);
    if (detail == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "adjust batch not found");
    }
    return CommonResult.success(detail);
  }

  /** 查询月度调价价格明细。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:detail')"
      + " or @ss.hasPermi('price:factor-adjust:list')"
      + " or @ss.hasPermi('price:finance-base:batch:list')"
      + " or @ss.hasPermi('price:finance-base:list')")
  @GetMapping("/prices")
  public CommonResult<FactorAdjustPricePageResponse> listPrices(
      @RequestParam(required = false) Long adjustBatchId,
      @RequestParam(required = false) Long factorIdentityId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    FactorAdjustPriceQueryRequest request = new FactorAdjustPriceQueryRequest();
    request.setAdjustBatchId(adjustBatchId);
    request.setFactorIdentityId(factorIdentityId);
    request.setKeyword(keyword);
    request.setStatus(status);
    request.setLimit(limit);
    request.setPage(page);
    request.setPageSize(pageSize);
    return CommonResult.success(queryService.pagePrices(request));
  }

  /** 影响因素表月度价格列表，补充最近调价批次和最近调价值。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:list')"
      + " or @ss.hasPermi('price:finance-base:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/monthly-prices")
  public CommonResult<FactorMonthlyPriceListPageResponse> listMonthlyPrices(
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam("businessUnitType") String businessUnitType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sourceTag,
      @RequestParam(required = false) String latestAdjustUsageScope,
      @RequestParam(required = false) String latestAdjustedBy,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    try {
      FactorMonthlyPriceListQueryRequest request = new FactorMonthlyPriceListQueryRequest();
      request.setPricingMonth(pricingMonth);
      request.setBusinessUnitType(businessUnitType);
      request.setKeyword(keyword);
      request.setSourceTag(sourceTag);
      request.setLatestAdjustUsageScope(latestAdjustUsageScope);
      request.setLatestAdjustedBy(latestAdjustedBy);
      request.setPage(page);
      request.setPageSize(pageSize);
      return CommonResult.success(queryService.pageMonthlyPrices(request));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  /** 导出影响因素月度调价模板，隐藏系统 ID 供回传精准匹配。 */
  @PreAuthorize("@ss.hasPermi('price:factor-adjust:export')"
      + " or @ss.hasPermi('price:finance-base:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/export-template")
  public void exportTemplate(
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam("businessUnitType") String businessUnitType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long adjustBatchId,
      HttpServletResponse response)
      throws IOException {
    try {
      byte[] bytes = templateExportService.exportTemplate(
          pricingMonth, businessUnitType, keyword, adjustBatchId);
      String fileName = "factor-adjust-template-" + pricingMonth + ".xlsx";
      String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
      response.setContentType(CONTENT_TYPE_XLSX);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
      response.getOutputStream().write(bytes);
    } catch (IllegalArgumentException ex) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
    }
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }
}
