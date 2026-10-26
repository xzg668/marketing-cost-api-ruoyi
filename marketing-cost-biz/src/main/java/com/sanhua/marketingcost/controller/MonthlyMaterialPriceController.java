package com.sanhua.marketingcost.controller;

import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQuery.*;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monthly-material-prices")
public class MonthlyMaterialPriceController {
  private final MonthlyPriceQueryService service;

  public MonthlyMaterialPriceController(MonthlyPriceQueryService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:monthly-material:list')")
  public CommonResult<Page<Entry>> list(
      @RequestParam String month,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String organization,
      @RequestParam(required = false) String pricingType,
      @RequestParam(required = false) String specification,
      @RequestParam(required = false) String unit,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String taxBasis,
      @RequestParam(required = false) String supplier,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        service.list(
            unit(),
            new Filter(
                month,
                materialCode,
                organization,
                pricingType,
                specification,
                unit,
                currency,
                taxBasis,
                supplier,
                page,
                size)));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('price:monthly-material:query')")
  public CommonResult<Detail> detail(@PathVariable long id) {
    return CommonResult.success(service.detail(unit(), id));
  }

  @GetMapping("/{id}/references")
  @PreAuthorize(
      "@ss.hasPermi('price:monthly-material:references') and @ss.hasPermi('ingest:quote:list')")
  public CommonResult<Page<Reference>> references(
      @PathVariable long id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(service.references(unit(), id, page, size));
  }

  private static String unit() {
    return BusinessUnitContext.getCurrentBusinessUnitType();
  }
}
