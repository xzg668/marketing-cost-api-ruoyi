package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.AuxRateItemImportRequest;
import com.sanhua.marketingcost.dto.AuxRateItemPageResponse;
import com.sanhua.marketingcost.dto.AuxRateItemRequest;
import com.sanhua.marketingcost.entity.AuxRateItem;
import com.sanhua.marketingcost.service.AuxRateItemService;
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

/**
 * 辅助费率控制器 - 管理辅助费率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/aux-rate-items")
public class AuxRateItemController {
  private final AuxRateItemService auxRateItemService;

  public AuxRateItemController(AuxRateItemService auxRateItemService) {
    this.auxRateItemService = auxRateItemService;
  }

  /** 查询辅助费率列表 */
  @PreAuthorize("@ss.hasPermi('base:aux-rate:list')")
  @GetMapping
  public CommonResult<AuxRateItemPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<AuxRateItem> pager = auxRateItemService.page(materialCode, period, current, size);
    return CommonResult.success(new AuxRateItemPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增辅助费率 */
  @PreAuthorize("@ss.hasPermi('base:aux-rate:add')")
  @PostMapping
  public CommonResult<AuxRateItem> create(@RequestBody AuxRateItemRequest request) {
    AuxRateItem created = auxRateItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改辅助费率 */
  @PreAuthorize("@ss.hasPermi('base:aux-rate:edit')")
  @PatchMapping("/{id}")
  public CommonResult<AuxRateItem> update(
      @PathVariable Long id,
      @RequestBody AuxRateItemRequest request) {
    AuxRateItem updated = auxRateItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"aux rate item not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除辅助费率 */
  @PreAuthorize("@ss.hasPermi('base:aux-rate:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(auxRateItemService.delete(id));
  }

  /** 导入辅助费率数据 */
  @PreAuthorize("@ss.hasPermi('base:aux-rate:import')")
  @PostMapping("/import")
  public CommonResult<List<AuxRateItem>> importItems(
      @RequestBody AuxRateItemImportRequest request) {
    return CommonResult.success(auxRateItemService.importItems(request));
  }
}
