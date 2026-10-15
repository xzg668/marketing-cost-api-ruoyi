package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.impl.OriginalPriceTrialService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OriginalPriceTrialController {
  private final OriginalPriceTrialService trials;

  public OriginalPriceTrialController(OriginalPriceTrialService trials) {
    this.trials = trials;
  }

  @PostMapping("/price-linked/original-effective-prices")
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  public CommonResult<java.util.List<OriginalPriceTrialService.EffectiveRow>> linkedEffective(
      @RequestBody OriginalPriceTrialService.BatchRequest request) {
    return CommonResult.success(trials.effective("LINKED", request));
  }

  @PostMapping("/price-range/original-effective-prices")
  @PreAuthorize("@ss.hasPermi('price:range:list')")
  public CommonResult<java.util.List<OriginalPriceTrialService.EffectiveRow>> rangeEffective(
      @RequestBody OriginalPriceTrialService.BatchRequest request) {
    return CommonResult.success(trials.effective("RANGE", request));
  }

  @GetMapping("/price-linked/items/{id}/original-effective-price")
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  public CommonResult<OriginalPriceTrialService.LinkedTrial> linkedEffectiveDetail(
      @PathVariable Long id, @RequestParam String pricingMonth) {
    return CommonResult.success(
        trials.linked(id, new OriginalPriceTrialService.Request(pricingMonth, null)));
  }

  @GetMapping("/price-range/original-rules/{id}/effective-price")
  @PreAuthorize("@ss.hasPermi('price:range:list')")
  public CommonResult<OriginalPriceTrialService.RangeTrial> rangeEffectiveDetail(
      @PathVariable Long id, @RequestParam String pricingMonth) {
    return CommonResult.success(
        trials.range(id, new OriginalPriceTrialService.Request(pricingMonth, null)));
  }

  @PostMapping("/price-linked/items/{id}/original-trial")
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  public CommonResult<OriginalPriceTrialService.LinkedTrial> linked(
      @PathVariable Long id, @RequestBody OriginalPriceTrialService.Request request) {
    return CommonResult.success(trials.linked(id, request));
  }

  @PostMapping("/price-range/original-rules/{id}/trial")
  @PreAuthorize("@ss.hasPermi('price:range:list')")
  public CommonResult<OriginalPriceTrialService.RangeTrial> range(
      @PathVariable Long id, @RequestBody OriginalPriceTrialService.Request request) {
    return CommonResult.success(trials.range(id, request));
  }
}
