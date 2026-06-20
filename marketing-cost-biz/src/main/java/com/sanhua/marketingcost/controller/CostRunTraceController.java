package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;
import com.sanhua.marketingcost.service.CostRunTraceQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cost-run/detail/{costRunNo}/traces")
public class CostRunTraceController {

  private final CostRunTraceQueryService traceQueryService;

  public CostRunTraceController(CostRunTraceQueryService traceQueryService) {
    this.traceQueryService = traceQueryService;
  }

  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping
  public CommonResult<CostRunTraceListResponse> list(@PathVariable("costRunNo") String costRunNo) {
    if (!StringUtils.hasText(costRunNo)) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "costRunNo is required");
    }
    return CommonResult.success(traceQueryService.listByCostRunNo(costRunNo));
  }

  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/{traceId}")
  public CommonResult<CostRunTraceDetailDto> detail(
      @PathVariable("costRunNo") String costRunNo, @PathVariable("traceId") Long traceId) {
    if (!StringUtils.hasText(costRunNo)) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "costRunNo is required");
    }
    if (traceId == null || traceId <= 0) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "traceId is required");
    }
    CostRunTraceDetailDto dto = traceQueryService.getByCostRunNoAndId(costRunNo, traceId);
    if (dto == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.NOT_FOUND.getCode(), "trace not found");
    }
    return CommonResult.success(dto);
  }
}
