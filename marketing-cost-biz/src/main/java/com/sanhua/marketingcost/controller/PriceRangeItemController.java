package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemPageResponse;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.service.PriceRangeItemService;
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
 * 区间价格控制器 - 管理区间价格的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-range/items")
public class PriceRangeItemController {
  private final PriceRangeItemService priceRangeItemService;

  public PriceRangeItemController(PriceRangeItemService priceRangeItemService) {
    this.priceRangeItemService = priceRangeItemService;
  }

  /** 查询区间价格列表 */
  @PreAuthorize("@ss.hasPermi('price:range:list')")
  @GetMapping
  public CommonResult<PriceRangeItemPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String specModel,
      @RequestParam(required = false) String effectiveFrom,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<PriceRangeItem> pager =
        priceRangeItemService.page(materialCode, supplierCode, specModel, effectiveFrom, current, size);
    return CommonResult.success(new PriceRangeItemPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:add')")
  @PostMapping
  public CommonResult<PriceRangeItem> create(@RequestBody PriceRangeItemUpdateRequest request) {
    PriceRangeItem created = priceRangeItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:edit')")
  @PatchMapping("/{id}")
  public CommonResult<PriceRangeItem> update(
      @PathVariable Long id,
      @RequestBody PriceRangeItemUpdateRequest request) {
    PriceRangeItem updated = priceRangeItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"range item not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceRangeItemService.delete(id));
  }

  /** 导入区间价格数据 */
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @PostMapping("/import")
  public CommonResult<List<PriceRangeItem>> importItems(
      @RequestBody PriceRangeItemImportRequest request) {
    return CommonResult.success(priceRangeItemService.importItems(request));
  }
}
