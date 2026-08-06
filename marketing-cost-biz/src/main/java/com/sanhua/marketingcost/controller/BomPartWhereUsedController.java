package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.BomPartWhereUsedItemResponse;
import com.sanhua.marketingcost.service.BomPartWhereUsedService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** “物料使用查询”页面接口，仅表达当前有效 BOM 潜在影响。 */
@RestController
@RequestMapping("/api/v1/base/u9/material-usage")
public class BomPartWhereUsedController {
  private final BomPartWhereUsedService service;

  public BomPartWhereUsedController(BomPartWhereUsedService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('base:u9-material-usage:list')")
  @GetMapping
  public CommonResult<PageResult<BomPartWhereUsedItemResponse>> page(
      @RequestParam String organizationCode,
      @RequestParam(required = false) String partCode,
      @RequestParam(required = false) String topProductCode,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "50") Integer pageSize) {
    int current = page == null ? 1 : page;
    int size = pageSize == null ? 50 : pageSize;
    return CommonResult.success(
        service.page(
            organizationCode,
            partCode,
            topProductCode,
            current,
            size));
  }
}
