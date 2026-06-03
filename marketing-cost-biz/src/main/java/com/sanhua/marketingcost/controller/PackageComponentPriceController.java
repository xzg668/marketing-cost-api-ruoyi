package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentBulkGenerateRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentBulkGenerateResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentBulkGenerateResult;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPricePageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPriceQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotQueryRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.service.PackageComponentPriceQueryService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

  private static final String PACKAGE_PARENT_ROW_TYPE = "PACKAGE_PARENT";
  private static final String DEFAULT_BOM_PURPOSE = "主制造";
  private static final String DEFAULT_SOURCE_TYPE = "U9";

  private final PackageComponentPriceQueryService queryService;
  private final PackageComponentPriceService priceService;
  private final PackageComponentSnapshotService snapshotService;
  private final BomCostingRowMapper bomCostingRowMapper;

  public PackageComponentPriceController(
      PackageComponentPriceQueryService queryService,
      PackageComponentPriceService priceService,
      PackageComponentSnapshotService snapshotService,
      BomCostingRowMapper bomCostingRowMapper) {
    this.queryService = queryService;
    this.priceService = priceService;
    this.snapshotService = snapshotService;
    this.bomCostingRowMapper = bomCostingRowMapper;
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

  @PreAuthorize("@ss.hasPermi('price:package-component:generate')")
  @PostMapping("/prices/generate-by-oa")
  public CommonResult<PackageComponentBulkGenerateResponse> generateByOa(
      @RequestBody PackageComponentBulkGenerateRequest request) {
    if (request == null || !StringUtils.hasText(request.getOaNo())) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "oaNo is required");
    }
    List<BomCostingRow> packageRows = loadPackageRows(request);
    PackageComponentBulkGenerateResponse response = new PackageComponentBulkGenerateResponse();
    response.setTotalCount(packageRows.size());
    List<PackageComponentBulkGenerateResult> records = new ArrayList<>();
    int successCount = 0;
    int failedCount = 0;
    for (BomCostingRow row : packageRows) {
      PackageComponentBulkGenerateResult record = new PackageComponentBulkGenerateResult();
      record.setOaNo(row.getOaNo());
      record.setTopProductCode(row.getTopProductCode());
      record.setPackageMaterialCode(row.getMaterialCode());
      record.setPackageMaterialName(row.getMaterialName());
      record.setPeriodMonth(effectivePeriodMonth(request, row));
      try {
        PackagePriceResult generated = priceService.ensurePrice(toPackagePriceRequest(request, row));
        fillBulkGenerateRecord(record, generated);
        successCount++;
      } catch (RuntimeException ex) {
        record.setStatus("FAILED");
        record.setComplete(false);
        record.setMessage(ex.getMessage());
        failedCount++;
      }
      records.add(record);
    }
    response.setSuccessCount(successCount);
    response.setFailedCount(failedCount);
    response.setRecords(records);
    return CommonResult.success(response);
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

  private List<BomCostingRow> loadPackageRows(PackageComponentBulkGenerateRequest request) {
    List<BomCostingRow> rows =
        bomCostingRowMapper.selectList(
            Wrappers.<BomCostingRow>lambdaQuery()
                .eq(BomCostingRow::getOaNo, trim(request.getOaNo()))
                .eq(BomCostingRow::getSettlementRowType, PACKAGE_PARENT_ROW_TYPE)
                .eq(StringUtils.hasText(request.getTopProductCode()),
                    BomCostingRow::getTopProductCode,
                    trim(request.getTopProductCode()))
                .orderByAsc(BomCostingRow::getTopProductCode)
                .orderByAsc(BomCostingRow::getMaterialCode)
                .orderByAsc(BomCostingRow::getId));
    Map<String, BomCostingRow> deduped = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (!StringUtils.hasText(row.getTopProductCode()) || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      deduped.putIfAbsent(row.getTopProductCode() + "|" + row.getMaterialCode(), row);
    }
    return List.copyOf(deduped.values());
  }

  private PackagePriceRequest toPackagePriceRequest(
      PackageComponentBulkGenerateRequest request, BomCostingRow row) {
    PackagePriceRequest priceRequest = new PackagePriceRequest();
    priceRequest.setPackageMaterialCode(row.getMaterialCode());
    priceRequest.setPeriodMonth(effectivePeriodMonth(request, row));
    priceRequest.setOaNo(row.getOaNo());
    priceRequest.setTopProductCode(row.getTopProductCode());
    priceRequest.setBomPurpose(
        StringUtils.hasText(request.getBomPurpose()) ? trim(request.getBomPurpose()) : DEFAULT_BOM_PURPOSE);
    priceRequest.setSourceType(
        StringUtils.hasText(request.getSourceType()) ? trim(request.getSourceType()) : DEFAULT_SOURCE_TYPE);
    priceRequest.setAsOfDate(request.getAsOfDate());
    priceRequest.setPriceAsOfTime(request.getPriceAsOfTime());
    priceRequest.setForceRefresh(request.isForceRefresh());
    return priceRequest;
  }

  private String effectivePeriodMonth(PackageComponentBulkGenerateRequest request, BomCostingRow row) {
    if (StringUtils.hasText(request.getPeriodMonth())) {
      return trim(request.getPeriodMonth());
    }
    return row == null ? null : row.getPeriodMonth();
  }

  private void fillBulkGenerateRecord(
      PackageComponentBulkGenerateResult record, PackagePriceResult generated) {
    PackageComponentPrice price = generated == null ? null : generated.getPrice();
    if (price != null) {
      record.setStatus(price.getPriceStatus());
      record.setComplete(Boolean.TRUE.equals(price.getPriceComplete()));
      record.setTotalPrice(price.getTotalPrice());
    } else if (generated != null) {
      record.setStatus(generated.getStatus());
      record.setComplete(generated.isComplete());
    }
    List<String> warnings = generated == null ? List.of() : generated.getWarnings();
    record.setMessage(warnings == null || warnings.isEmpty() ? null : warnings.get(0));
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }
}
