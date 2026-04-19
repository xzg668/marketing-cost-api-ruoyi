package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
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
 * 联动价格明细控制器 - 管理联动价格明细的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedItemController {
  private final PriceLinkedItemService priceLinkedItemService;

  public PriceLinkedItemController(PriceLinkedItemService priceLinkedItemService) {
    this.priceLinkedItemService = priceLinkedItemService;
  }

  /** 查询联动价格明细列表 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items")
  public CommonResult<List<PriceLinkedItemDto>> list(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String materialCode) {
    return CommonResult.success(priceLinkedItemService.list(pricingMonth, materialCode));
  }

  /** 修改联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:edit')")
  @PatchMapping("/items/{id}")
  public CommonResult<PriceLinkedItemDto> update(
      @PathVariable Long id,
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto updated = priceLinkedItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"linked item not found");
    }
    return CommonResult.success(updated);
  }

  /** 导入联动价格明细数据 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import")
  public CommonResult<List<PriceLinkedItemDto>> importItems(
      @RequestBody PriceLinkedItemImportRequest request) {
    return CommonResult.success(priceLinkedItemService.importItems(request));
  }

  /** 新增联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:add')")
  @PostMapping("/items")
  public CommonResult<PriceLinkedItemDto> create(
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto created = priceLinkedItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 删除联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:remove')")
  @DeleteMapping("/items/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceLinkedItemService.delete(id));
  }


}
