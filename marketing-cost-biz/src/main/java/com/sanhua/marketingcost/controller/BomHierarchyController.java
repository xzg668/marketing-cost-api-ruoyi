package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.service.BomHierarchyQueryService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** EasyData BOM 层级事实的只读树查询接口。 */
@RestController
@RequestMapping("/api/v1/bom")
public class BomHierarchyController {

  private final BomHierarchyQueryService queryService;

  public BomHierarchyController(BomHierarchyQueryService queryService) {
    this.queryService = queryService;
  }

  /**
   * 按顶层料号查嵌套树（供 T6 前端树查看器）。
   *
   * <p>{@code asOfDate} 决定拿哪个版本（多版本并存时用生效期过滤）；不传默认当天。
   */
  @PreAuthorize("@ss.hasPermi('base:bom:list')")
  @GetMapping("/hierarchy/{topProductCode}")
  public CommonResult<BomHierarchyTreeDto> getHierarchy(
      @PathVariable String topProductCode,
      @RequestParam(required = false) String bomPurpose,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
      @RequestParam(required = false, defaultValue = "U9") String sourceType,
      @RequestParam String priceOrgCode) {
    return CommonResult.success(
        queryService.getHierarchyTree(
            topProductCode, bomPurpose, asOfDate, sourceType, priceOrgCode));
  }
}
