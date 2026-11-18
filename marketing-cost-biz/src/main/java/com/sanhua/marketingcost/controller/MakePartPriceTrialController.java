package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.MakePartPriceTrialService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/make-part-price-calc")
public class MakePartPriceTrialController {
  private final MakePartPriceTrialService service;

  public MakePartPriceTrialController(MakePartPriceTrialService service) { this.service = service; }

  @GetMapping("/{id}/trace")
  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  public CommonResult<MakePartPriceTrialService.Result> trace(@PathVariable long id) {
    return CommonResult.success(service.calculate(id, null));
  }

  @PostMapping("/{id}/trial")
  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  public CommonResult<MakePartPriceTrialService.Result> trial(@PathVariable long id,
      @RequestBody MakePartPriceTrialService.Request request) {
    return CommonResult.success(service.calculate(id, request));
  }
}
