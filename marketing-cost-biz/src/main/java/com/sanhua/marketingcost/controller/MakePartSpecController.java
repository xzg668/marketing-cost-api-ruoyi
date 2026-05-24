package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MakePartSpecImportRequest;
import com.sanhua.marketingcost.dto.MakePartSpecPageResponse;
import com.sanhua.marketingcost.dto.MakePartSpecUpdateRequest;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.service.MakePartSpecService;
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

/**
 * 旧自制件工艺规格控制器。
 *
 * <p>MPPG-10 后旧自制件规格只作历史兼容，实时成本新口径不读取
 * lp_make_part_spec.raw_unit_price / recycle_unit_price；日常业务请使用制造件价格生成页面。
 */
@RestController
@RequestMapping("/api/v1/make-part-spec/items")
public class MakePartSpecController {

  private static final String LEGACY_DISABLED_MESSAGE =
      "旧自制件规格维护入口已下线，请使用制造件价格生成";

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
    return legacyWriteDisabled();
  }

  @PreAuthorize("@ss.hasPermi('make:part:edit')")
  @PatchMapping("/{id}")
  public CommonResult<MakePartSpec> update(
      @PathVariable Long id, @RequestBody MakePartSpecUpdateRequest request) {
    return legacyWriteDisabled();
  }

  @PreAuthorize("@ss.hasPermi('make:part:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), LEGACY_DISABLED_MESSAGE);
  }

  @PreAuthorize("@ss.hasPermi('make:part:import')")
  @PostMapping("/import")
  public CommonResult<Object> importItems(@RequestBody MakePartSpecImportRequest request) {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), LEGACY_DISABLED_MESSAGE);
  }

  private CommonResult<MakePartSpec> legacyWriteDisabled() {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), LEGACY_DISABLED_MESSAGE);
  }
}
