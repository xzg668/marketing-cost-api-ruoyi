package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.pricing.MaterialSpecialPriceConfiguration;
import java.security.Principal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/material-price-types/special")
public class MaterialSpecialPriceController {
  private final MaterialSpecialPriceConfiguration service;
  public MaterialSpecialPriceController(MaterialSpecialPriceConfiguration service) { this.service=service; }
  @GetMapping
  @PreAuthorize("@ss.hasPermi('base:material-price-type:list')")
  public CommonResult<List<Map<String,Object>>> list(@RequestParam(required=false) String search) { return CommonResult.success(service.list(search)); }
  @GetMapping("/material")
  @PreAuthorize("@ss.hasPermi('base:material-price-type:list')")
  public CommonResult<List<Map<String,Object>>> material(@RequestParam String code) { return CommonResult.success(service.material(code)); }
  @PostMapping
  @PreAuthorize("@ss.hasPermi('base:material-price-type:edit')")
  public CommonResult<Boolean> create(@RequestBody MaterialSpecialPriceConfiguration.Input input,Principal principal) {
    service.save(null,input,principal.getName());return CommonResult.success(true);
  }
  @PutMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('base:material-price-type:edit')")
  public CommonResult<Boolean> update(@PathVariable Long id,@RequestBody MaterialSpecialPriceConfiguration.Input input,Principal principal) {
    service.save(id,input,principal.getName());return CommonResult.success(true);
  }
}
