package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.service.BomFlattenService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOM 阶段 C 拍平接口。
 *
 * <p>{@code POST /api/v1/bom/flatten} —— 按 asOfDate 把 raw_hierarchy 快照 + 过滤规则
 * 合成 lp_bom_costing_row（见父设计文档 §E.3）。
 */
@RestController
@RequestMapping("/api/v1/bom")
public class BomFlattenController {

  private final BomFlattenService flattenService;

  public BomFlattenController(BomFlattenService flattenService) {
    this.flattenService = flattenService;
  }

  @PreAuthorize("@ss.hasPermi('base:bom:flatten')")
  @PostMapping("/flatten")
  public CommonResult<FlattenResult> flatten(@RequestBody FlattenRequest request) {
    return CommonResult.success(flattenService.flatten(request));
  }
}
