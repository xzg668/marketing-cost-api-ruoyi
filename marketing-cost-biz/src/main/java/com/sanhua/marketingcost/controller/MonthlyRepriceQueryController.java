package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceActiveLockDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePageResponse;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskDto;
import com.sanhua.marketingcost.dto.MonthlyRepriceTaskQueryRequest;
import java.util.List;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import com.sanhua.marketingcost.service.MonthlyRepriceQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monthly-reprice")
public class MonthlyRepriceQueryController {

  // 查询入口允许复核角色和现有成本查询角色进入，具体可见范围在 QueryService 里按批次状态和业务单元再收窄。
  static final String QUERY_PERMISSION =
      "@ss.hasAnyRole('ADMIN','BU_DIRECTOR')"
          + " or @ss.hasAnyPermi('price:monthly-reprice:review','price:monthly-reprice:list','cost:run:list')";

  private final MonthlyRepriceQueryService queryService;
  private final MonthlyRepriceProgressService progressService;

  public MonthlyRepriceQueryController(
      MonthlyRepriceQueryService queryService, MonthlyRepriceProgressService progressService) {
    this.queryService = queryService;
    this.progressService = progressService;
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/active-lock")
  public CommonResult<MonthlyRepriceActiveLockDto> activeLock() {
    return CommonResult.success(queryService.getActiveLock());
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches")
  public CommonResult<MonthlyRepricePageResponse<MonthlyRepriceBatchDto>> listBatches(
      @RequestParam(required = false) String repriceNo,
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String createdBy,
      @RequestParam(required = false) String confirmedBy,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDirection) {
    MonthlyRepriceBatchQueryRequest request = new MonthlyRepriceBatchQueryRequest();
    request.setRepriceNo(repriceNo);
    request.setPricingMonth(pricingMonth);
    request.setBusinessUnitType(businessUnitType);
    request.setStatus(status);
    request.setCreatedBy(createdBy);
    request.setConfirmedBy(confirmedBy);
    request.setPage(page);
    request.setPageSize(pageSize);
    request.setSortBy(sortBy);
    request.setSortDirection(sortDirection);
    return CommonResult.success(queryService.pageBatches(request));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}")
  public CommonResult<MonthlyRepriceBatchDto> getBatch(@PathVariable("repriceNo") String repriceNo) {
    MonthlyRepriceBatchDto batch = queryService.getBatch(repriceNo);
    if (batch == null) {
      return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "monthly reprice batch not found");
    }
    return CommonResult.success(batch);
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}/progress")
  public CommonResult<MonthlyRepriceProgressSnapshot> progress(
      @PathVariable("repriceNo") String repriceNo) {
    MonthlyRepriceBatchDto batch = queryService.getBatch(repriceNo);
    if (batch == null) {
      return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "monthly reprice batch not found");
    }
    return CommonResult.success(progressService.getProgress(repriceNo));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}/tasks")
  public CommonResult<MonthlyRepricePageResponse<MonthlyRepriceTaskDto>> tasks(
      @PathVariable("repriceNo") String repriceNo,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDirection) {
    MonthlyRepriceTaskQueryRequest request = new MonthlyRepriceTaskQueryRequest();
    request.setStatus(status);
    request.setKeyword(keyword);
    request.setPage(page);
    request.setPageSize(pageSize);
    request.setSortBy(sortBy);
    request.setSortDirection(sortDirection);
    return CommonResult.success(queryService.pageTasks(repriceNo, request));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}/results")
  public CommonResult<MonthlyRepricePageResponse<MonthlyRepriceResultDto>> results(
      @PathVariable("repriceNo") String repriceNo,
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String productCode,
      @RequestParam(required = false) String customerName,
      @RequestParam(required = false) String calcStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDirection) {
    MonthlyRepriceResultQueryRequest request = new MonthlyRepriceResultQueryRequest();
    request.setOaNo(oaNo);
    request.setProductCode(productCode);
    request.setCustomerName(customerName);
    request.setCalcStatus(calcStatus);
    request.setKeyword(keyword);
    request.setPage(page);
    request.setPageSize(pageSize);
    request.setSortBy(sortBy);
    request.setSortDirection(sortDirection);
    return CommonResult.success(queryService.pageResults(repriceNo, request));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}/results/{resultId}/part-items")
  public CommonResult<List<MonthlyRepricePartItemDto>> partItems(
      @PathVariable("repriceNo") String repriceNo, @PathVariable("resultId") Long resultId) {
    return CommonResult.success(queryService.listPartItems(repriceNo, resultId));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/batches/{repriceNo}/results/{resultId}/cost-items")
  public CommonResult<List<MonthlyRepriceCostItemDto>> costItems(
      @PathVariable("repriceNo") String repriceNo, @PathVariable("resultId") Long resultId) {
    return CommonResult.success(queryService.listCostItems(repriceNo, resultId));
  }

  @PreAuthorize(QUERY_PERMISSION)
  @GetMapping("/audit-logs")
  public CommonResult<MonthlyRepricePageResponse<MonthlyRepriceAuditLogDto>> auditLogs(
      @RequestParam(required = false) String repriceNo,
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String operationType,
      @RequestParam(required = false) String operatorName,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize,
      @RequestParam(required = false) String sortBy,
      @RequestParam(required = false) String sortDirection) {
    MonthlyRepriceAuditLogQueryRequest request = new MonthlyRepriceAuditLogQueryRequest();
    request.setRepriceNo(repriceNo);
    request.setPricingMonth(pricingMonth);
    request.setBusinessUnitType(businessUnitType);
    request.setOperationType(operationType);
    request.setOperatorName(operatorName);
    request.setPage(page);
    request.setPageSize(pageSize);
    request.setSortBy(sortBy);
    request.setSortDirection(sortDirection);
    return CommonResult.success(queryService.pageAuditLogs(request));
  }
}
