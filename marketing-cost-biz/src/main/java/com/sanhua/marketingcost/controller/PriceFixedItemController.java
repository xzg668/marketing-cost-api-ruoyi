package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemPageResponse;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.service.PriceFixedItemService;
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
 * 固定价格控制器 - 管理固定价格的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-fixed/items")
public class PriceFixedItemController {
  private final PriceFixedItemService priceFixedItemService;

  public PriceFixedItemController(PriceFixedItemService priceFixedItemService) {
    this.priceFixedItemService = priceFixedItemService;
  }

  /** 查询固定价格列表 */
  @PreAuthorize("@ss.hasPermi('price:fixed:list')")
  @GetMapping
  public CommonResult<PriceFixedItemPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<PriceFixedItem> pager =
        priceFixedItemService.page(materialCode, supplierCode, current, size);
    return CommonResult.success(new PriceFixedItemPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增固定价格 */
  @PreAuthorize("@ss.hasPermi('price:fixed:add')")
  @PostMapping
  public CommonResult<PriceFixedItem> create(@RequestBody PriceFixedItemUpdateRequest request) {
    PriceFixedItem created = priceFixedItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改固定价格 */
  @PreAuthorize("@ss.hasPermi('price:fixed:edit')")
  @PatchMapping("/{id}")
  public CommonResult<PriceFixedItem> update(
      @PathVariable Long id,
      @RequestBody PriceFixedItemUpdateRequest request) {
    PriceFixedItem updated = priceFixedItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"fixed item not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除固定价格 */
  @PreAuthorize("@ss.hasPermi('price:fixed:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceFixedItemService.delete(id));
  }

  /** 导入固定价格数据 */
  @PreAuthorize("@ss.hasPermi('price:fixed:import')")
  @PostMapping("/import")
  public CommonResult<List<PriceFixedItem>> importItems(
      @RequestBody PriceFixedItemImportRequest request) {
    return CommonResult.success(priceFixedItemService.importItems(request));
  }
}
