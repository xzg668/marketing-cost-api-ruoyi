package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.costing.CostAmountCompositionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cost-run/detail/{costRunNo}/composition")
public class CostAmountCompositionController {
  private final CostAmountCompositionService service;
  public CostAmountCompositionController(CostAmountCompositionService service) { this.service=service; }
  @GetMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public CommonResult<CostAmountCompositionService.View> read(@PathVariable String costRunNo,
      @RequestParam(required=false) Long costItemId, @RequestParam(required=false) Long partItemId) {
    return CommonResult.success(service.read(costRunNo,costItemId,partItemId));
  }
}
