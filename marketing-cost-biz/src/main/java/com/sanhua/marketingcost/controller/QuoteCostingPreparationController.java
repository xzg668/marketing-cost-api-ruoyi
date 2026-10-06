package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService.Request;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService.State;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService.Outcome;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/costing-preparation")
public class QuoteCostingPreparationController {
  private final QuoteCostingPreparationService service;
  public QuoteCostingPreparationController(QuoteCostingPreparationService service) { this.service = service; }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<State> state(@PathVariable String oaNo, @RequestParam(required = false) Long itemId) {
    return CommonResult.success(service.state(oaNo, itemId));
  }

  @PostMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<Outcome> prepareAndCost(@PathVariable String oaNo, @RequestBody Request request,
      Authentication authentication) {
    try {
      return CommonResult.success(service.prepareAndCost(oaNo, request, authentication.getName()));
    } catch (IllegalArgumentException | IllegalStateException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }
}
