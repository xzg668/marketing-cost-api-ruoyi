package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapPageResponse;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.service.PriceScrapService;
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

/** 废料回收价 控制器 (V48) */
@RestController
@RequestMapping("/api/v1/price-scrap/items")
public class PriceScrapController {

  private final PriceScrapService priceScrapService;

  public PriceScrapController(PriceScrapService priceScrapService) {
    this.priceScrapService = priceScrapService;
  }

  /** 查询：可按 scrapCode / pricingMonth 过滤 */
  @PreAuthorize("@ss.hasPermi('price:scrap:list')")
  @GetMapping
  public CommonResult<PriceScrapPageResponse> list(
      @RequestParam(required = false) String scrapCode,
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<PriceScrap> pager = priceScrapService.page(scrapCode, pricingMonth, current, size);
    return CommonResult.success(new PriceScrapPageResponse(pager.getTotal(), pager.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('price:scrap:add')")
  @PostMapping
  public CommonResult<PriceScrap> create(@RequestBody PriceScrapUpdateRequest request) {
    PriceScrap created = priceScrapService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "create failed");
    }
    return CommonResult.success(created);
  }

  @PreAuthorize("@ss.hasPermi('price:scrap:edit')")
  @PatchMapping("/{id}")
  public CommonResult<PriceScrap> update(
      @PathVariable Long id, @RequestBody PriceScrapUpdateRequest request) {
    PriceScrap updated = priceScrapService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "scrap item not found");
    }
    return CommonResult.success(updated);
  }

  @PreAuthorize("@ss.hasPermi('price:scrap:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceScrapService.delete(id));
  }

  @PreAuthorize("@ss.hasPermi('price:scrap:import')")
  @PostMapping("/import")
  public CommonResult<List<PriceScrap>> importItems(@RequestBody PriceScrapImportRequest request) {
    return CommonResult.success(priceScrapService.importItems(request));
  }
}
