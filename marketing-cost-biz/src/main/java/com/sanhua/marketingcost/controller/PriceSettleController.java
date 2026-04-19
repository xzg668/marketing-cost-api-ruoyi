package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceSettleDetailResponse;
import com.sanhua.marketingcost.dto.PriceSettleImportRequest;
import com.sanhua.marketingcost.dto.PriceSettleItemUpdateRequest;
import com.sanhua.marketingcost.dto.PriceSettlePageResponse;
import com.sanhua.marketingcost.dto.PriceSettleUpdateRequest;
import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.entity.PriceSettleItem;
import com.sanhua.marketingcost.service.PriceSettleService;
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
 * 价格结算控制器 - 管理价格结算单及结算明细的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-settle")
public class PriceSettleController {
  private final PriceSettleService priceSettleService;

  public PriceSettleController(PriceSettleService priceSettleService) {
    this.priceSettleService = priceSettleService;
  }

  /** 查询价格结算列表 */
  @PreAuthorize("@ss.hasPermi('price:settle:list')")
  @GetMapping
  public CommonResult<PriceSettlePageResponse> list(
      @RequestParam(required = false) String buyer,
      @RequestParam(required = false) String month,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<PriceSettle> pager = priceSettleService.page(buyer, month, current, size);
    return CommonResult.success(new PriceSettlePageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 查询价格结算详情 */
  @PreAuthorize("@ss.hasPermi('price:settle:query')")
  @GetMapping("/{id}")
  public CommonResult<PriceSettleDetailResponse> detail(@PathVariable Long id) {
    PriceSettleDetailResponse detail = priceSettleService.detail(id);
    if (detail == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"settle not found");
    return CommonResult.success(detail);
  }

  /** 新增价格结算单 */
  @PreAuthorize("@ss.hasPermi('price:settle:add')")
  @PostMapping
  public CommonResult<PriceSettle> create(@RequestBody PriceSettleUpdateRequest request) {
    PriceSettle created = priceSettleService.create(request);
    if (created == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    return CommonResult.success(created);
  }

  /** 修改价格结算单 */
  @PreAuthorize("@ss.hasPermi('price:settle:edit')")
  @PatchMapping("/{id}")
  public CommonResult<PriceSettle> update(
      @PathVariable Long id,
      @RequestBody PriceSettleUpdateRequest request) {
    PriceSettle updated = priceSettleService.update(id, request);
    if (updated == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"settle not found");
    return CommonResult.success(updated);
  }

  /** 删除价格结算单 */
  @PreAuthorize("@ss.hasPermi('price:settle:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceSettleService.delete(id));
  }

  /** 导入价格结算数据 */
  @PreAuthorize("@ss.hasPermi('price:settle:import')")
  @PostMapping("/import")
  public CommonResult<PriceSettleDetailResponse> importSettle(
      @RequestBody PriceSettleImportRequest request) {
    PriceSettleDetailResponse result = priceSettleService.importSettle(request);
    if (result == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"import failed");
    return CommonResult.success(result);
  }

  /** 新增结算明细项 */
  @PreAuthorize("@ss.hasPermi('price:settle:add')")
  @PostMapping("/{settleId}/items")
  public CommonResult<PriceSettleItem> createItem(
      @PathVariable Long settleId,
      @RequestBody PriceSettleItemUpdateRequest request) {
    PriceSettleItem created = priceSettleService.createItem(settleId, request);
    if (created == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create item failed");
    return CommonResult.success(created);
  }

  /** 修改结算明细项 */
  @PreAuthorize("@ss.hasPermi('price:settle:edit')")
  @PatchMapping("/items/{id}")
  public CommonResult<PriceSettleItem> updateItem(
      @PathVariable Long id,
      @RequestBody PriceSettleItemUpdateRequest request) {
    PriceSettleItem updated = priceSettleService.updateItem(id, request);
    if (updated == null) return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"item not found");
    return CommonResult.success(updated);
  }

  /** 删除结算明细项 */
  @PreAuthorize("@ss.hasPermi('price:settle:remove')")
  @DeleteMapping("/items/{id}")
  public CommonResult<Boolean> deleteItem(@PathVariable Long id) {
    return CommonResult.success(priceSettleService.deleteItem(id));
  }
}
