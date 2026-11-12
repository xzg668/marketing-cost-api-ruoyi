package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.scrap.ScrapPriceTrialService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scrap-prices")
public class ScrapPriceTrialController {
  private final ScrapPriceTrialService trials;

  public ScrapPriceTrialController(ScrapPriceTrialService trials) { this.trials = trials; }

  @PostMapping("/{id}/trial")
  @PreAuthorize("@ss.hasPermi('price:scrap:list')")
  public CommonResult<ScrapPriceTrialService.Result> trial(@PathVariable long id,
      @RequestBody ScrapPriceTrialService.Request request) {
    return CommonResult.success(trials.trial(BusinessUnitContext.getCurrentBusinessUnitType(), id, request));
  }
}
