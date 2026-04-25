package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import com.sanhua.marketingcost.service.BomHierarchyBuildService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOM 三层架构 · 阶段 B 构建层级 + 前端树查看接口。
 *
 * <p>鉴权沿用项目 {@code @ss.hasPermi(...)} 表达式。权限 code:
 * <ul>
 *   <li>{@code base:bom:build} 触发层级构建</li>
 *   <li>{@code base:bom:list} 查询嵌套树</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/bom")
public class BomHierarchyController {

  private final BomHierarchyBuildService buildService;

  public BomHierarchyController(BomHierarchyBuildService buildService) {
    this.buildService = buildService;
  }

  /** 按 importBatchId + bomPurpose 构建层级，写入 lp_bom_raw_hierarchy。 */
  @PreAuthorize("@ss.hasPermi('base:bom:build')")
  @PostMapping("/build-hierarchy")
  public CommonResult<BuildHierarchyResult> buildHierarchy(@RequestBody BuildHierarchyRequest req) {
    return CommonResult.success(buildService.build(req));
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
      @RequestParam(required = false, defaultValue = "U9") String sourceType) {
    return CommonResult.success(
        buildService.getHierarchyTree(topProductCode, bomPurpose, asOfDate, sourceType));
  }
}
