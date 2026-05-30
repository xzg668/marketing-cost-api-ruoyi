package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingProductPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageStructurePageResponse;
import com.sanhua.marketingcost.service.QuoteBomDetailQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-bom-details")
public class QuoteBomDetailQueryController {

  private final QuoteBomDetailQueryService queryService;

  public QuoteBomDetailQueryController(QuoteBomDetailQueryService queryService) {
    this.queryService = queryService;
  }

  @PreAuthorize("@ss.hasAnyPermi('bom-data:package-structure:list','ingest:quote-product-bom:list')")
  @GetMapping("/package-structures")
  public CommonResult<QuoteBomPackageStructurePageResponse> packageStructures(
      @RequestParam(required = false) String referenceFinishedCode,
      @RequestParam(required = false) String sourceTopProductCode,
      @RequestParam(required = false) String packageParentCode,
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    return CommonResult.success(
        queryService.pagePackageStructures(
            referenceFinishedCode, sourceTopProductCode, packageParentCode, periodMonth, page, pageSize));
  }

  @PreAuthorize("@ss.hasAnyPermi('bom-data:costing-row:list','base:bom:list','ingest:quote-product-bom:list')")
  @GetMapping("/costing-products")
  public CommonResult<QuoteBomCostingProductPageResponse> costingProducts(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String topProductCode,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    return CommonResult.success(
        queryService.pageCostingProducts(oaNo, topProductCode, materialCode, periodMonth, page, pageSize));
  }

  @PreAuthorize("@ss.hasAnyPermi('bom-data:costing-row:list','base:bom:list','ingest:quote-product-bom:list')")
  @GetMapping("/costing-rows")
  public CommonResult<QuoteBomCostingRowPageResponse> costingRows(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String topProductCode,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    return CommonResult.success(
        queryService.pageCostingRows(oaNo, topProductCode, materialCode, periodMonth, page, pageSize));
  }
}
