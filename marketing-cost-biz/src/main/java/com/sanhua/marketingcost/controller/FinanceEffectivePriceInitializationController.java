package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 升级时显式初始化既有公共原稿，复用财务基价导入权限和当前业务单元。 */
@RestController
@RequestMapping("/api/v1/finance-effective-prices")
public class FinanceEffectivePriceInitializationController {
  private final FinanceEffectivePriceService prices;

  public FinanceEffectivePriceInitializationController(FinanceEffectivePriceService prices) {
    this.prices = prices;
  }

  public record Request(String priceOrgCode) {}

  @PostMapping("/initialize")
  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  public CommonResult<FinanceEffectivePriceService.Generation> initialize(
      @RequestBody Request request) {
    if (request == null) throw new IllegalArgumentException("请选择初始化组织");
    return CommonResult.success(
        prices.initialize(
            BusinessUnitContext.getCurrentBusinessUnitType(), request.priceOrgCode()));
  }
}
