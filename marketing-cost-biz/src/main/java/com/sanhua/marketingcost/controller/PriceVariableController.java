package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.service.PriceVariableService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 价格变量控制器 - 查询联动价格变量列表
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceVariableController {
  private final PriceVariableService priceVariableService;

  public PriceVariableController(PriceVariableService priceVariableService) {
    this.priceVariableService = priceVariableService;
  }

  /** 查询价格变量列表 */
  @PreAuthorize("@ss.hasPermi('price:variable:list')")
  @GetMapping("/variables")
  public CommonResult<List<PriceVariable>> list(
      @RequestParam(required = false) String status) {
    return CommonResult.success(priceVariableService.list(status));
  }
}
