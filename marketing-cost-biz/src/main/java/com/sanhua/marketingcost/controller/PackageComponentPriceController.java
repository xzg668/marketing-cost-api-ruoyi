package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPricePageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPriceQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotQueryRequest;
import com.sanhua.marketingcost.service.PackageComponentPriceQueryService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/package-components")
public class PackageComponentPriceController {

  private final PackageComponentPriceQueryService queryService;
  private final PackageComponentPriceService priceService;
  private final PackageComponentSnapshotService snapshotService;

  public PackageComponentPriceController(
      PackageComponentPriceQueryService queryService,
      PackageComponentPriceService priceService,
      PackageComponentSnapshotService snapshotService) {
    this.queryService = queryService;
    this.priceService = priceService;
    this.snapshotService = snapshotService;
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:list')")
  @GetMapping("/prices")
  public CommonResult<PackageComponentPricePageResponse> listPrices(
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false) String packageMaterialCode,
      @RequestParam(required = false) String topProductCode,
      @RequestParam(required = false) String priceStatus,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    PackageComponentPriceQueryRequest request = new PackageComponentPriceQueryRequest();
    request.setPeriodMonth(periodMonth);
    request.setPackageMaterialCode(packageMaterialCode);
    request.setTopProductCode(topProductCode);
    request.setPriceStatus(priceStatus);
    request.setPage(page);
    request.setPageSize(pageSize);
    return CommonResult.success(queryService.pagePrices(request));
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:detail')")
  @GetMapping("/prices/{id}/details")
  public CommonResult<PackagePriceDetailResult> priceDetails(@PathVariable Long id) {
    PackagePriceDetailResult detail = priceService.getPriceDetail(id);
    if (detail == null || detail.getPrice() == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "package component price not found");
    }
    return CommonResult.success(detail);
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:generate')")
  @PostMapping("/prices/generate")
  public CommonResult<PackagePriceResult> generatePrice(@RequestBody PackagePriceRequest request) {
    if (request == null || !StringUtils.hasText(request.getPackageMaterialCode())) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "packageMaterialCode is required");
    }
    try {
      return CommonResult.success(priceService.ensurePrice(request));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:list')")
  @GetMapping("/snapshots")
  public CommonResult<PackageComponentSnapshotPageResponse> listSnapshots(
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false) String packageMaterialCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    PackageComponentSnapshotQueryRequest request = new PackageComponentSnapshotQueryRequest();
    request.setPeriodMonth(periodMonth);
    request.setPackageMaterialCode(packageMaterialCode);
    request.setStatus(status);
    request.setPage(page);
    request.setPageSize(pageSize);
    return CommonResult.success(queryService.pageSnapshots(request));
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:detail')")
  @GetMapping("/snapshots/{id}/details")
  public CommonResult<PackageSnapshotDetailResult> snapshotDetails(@PathVariable Long id) {
    PackageSnapshotDetailResult detail = snapshotService.getSnapshotDetail(id);
    if (detail == null || detail.getSnapshot() == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "package component snapshot not found");
    }
    return CommonResult.success(detail);
  }

  @PreAuthorize("@ss.hasPermi('price:package-component:gaps')")
  @GetMapping("/gaps")
  public CommonResult<PackageComponentGapPageResponse> listGaps(
      @RequestParam(required = false) String periodMonth,
      @RequestParam(required = false) String packageMaterialCode,
      @RequestParam(required = false) String gapType,
      @RequestParam(required = false) String oaPushStatus,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    PackageComponentGapQueryRequest request = new PackageComponentGapQueryRequest();
    request.setPeriodMonth(periodMonth);
    request.setPackageMaterialCode(packageMaterialCode);
    request.setGapType(gapType);
    request.setOaPushStatus(oaPushStatus);
    request.setPage(page);
    request.setPageSize(pageSize);
    return CommonResult.success(queryService.pageGaps(request));
  }
}
