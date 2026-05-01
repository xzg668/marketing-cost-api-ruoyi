package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartSpecImportRequest;
import com.sanhua.marketingcost.dto.MakePartSpecPageResponse;
import com.sanhua.marketingcost.dto.MakePartSpecUpdateRequest;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.service.MakePartSpecService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 自制件工艺规格 控制器 (V48 暴露 UI) */
@RestController
@RequestMapping("/api/v1/make-part-spec/items")
public class MakePartSpecController {

  private final MakePartSpecService makePartSpecService;

  public MakePartSpecController(MakePartSpecService makePartSpecService) {
    this.makePartSpecService = makePartSpecService;
  }

  @PreAuthorize("@ss.hasPermi('make:part:list')")
  @GetMapping
  public CommonResult<MakePartSpecPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<MakePartSpec> pager = makePartSpecService.page(materialCode, period, current, size);
    return CommonResult.success(new MakePartSpecPageResponse(pager.getTotal(), pager.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('make:part:add')")
  @PostMapping
  public CommonResult<MakePartSpec> create(@RequestBody MakePartSpecUpdateRequest request) {
    MakePartSpec created = makePartSpecService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "create failed");
    }
    return CommonResult.success(created);
  }

  @PreAuthorize("@ss.hasPermi('make:part:edit')")
  @PatchMapping("/{id}")
  public CommonResult<MakePartSpec> update(
      @PathVariable Long id, @RequestBody MakePartSpecUpdateRequest request) {
    MakePartSpec updated = makePartSpecService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "make part spec not found");
    }
    return CommonResult.success(updated);
  }

  @PreAuthorize("@ss.hasPermi('make:part:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(makePartSpecService.delete(id));
  }

  @PreAuthorize("@ss.hasPermi('make:part:import')")
  @PostMapping("/import")
  public CommonResult<List<MakePartSpec>> importItems(@RequestBody MakePartSpecImportRequest request) {
    return CommonResult.success(makePartSpecService.importItems(request));
  }
}
