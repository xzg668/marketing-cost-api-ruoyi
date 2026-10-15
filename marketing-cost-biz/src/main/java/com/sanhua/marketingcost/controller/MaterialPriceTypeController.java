package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/material-price-types")
public class MaterialPriceTypeController {
  private final MaterialPriceTypeService service;
  public MaterialPriceTypeController(MaterialPriceTypeService service) { this.service = service; }

  @PreAuthorize("@ss.hasPermi('base:material-price-type:list')")
  @GetMapping
  public CommonResult<MaterialPriceTypePageResponse> list(@RequestParam String period,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String priceOrgCode,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return CommonResult.success(service.page(materialCode, period, priceOrgCode, page, pageSize));
  }

  @PreAuthorize("@ss.hasPermi('base:material-price-type:edit')")
  @PatchMapping("/{id}/priority")
  public CommonResult<MaterialPriceTypePageResponse.Row> update(@PathVariable Long id,
      @RequestBody MaterialPriceTypeRequest request) {
    return CommonResult.success(service.update(id, request));
  }
}
