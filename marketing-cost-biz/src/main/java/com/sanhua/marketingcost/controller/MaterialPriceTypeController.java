package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
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
 * 物料价格类型控制器 - 管理物料价格类型的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/material-price-types")
public class MaterialPriceTypeController {
  private final MaterialPriceTypeService materialPriceTypeService;

  public MaterialPriceTypeController(MaterialPriceTypeService materialPriceTypeService) {
    this.materialPriceTypeService = materialPriceTypeService;
  }

  /** 查询物料价格类型列表 */
  @PreAuthorize("@ss.hasPermi('base:material-price-type:list')")
  @GetMapping
  public CommonResult<MaterialPriceTypePageResponse> list(
      @RequestParam(required = false) String billNo,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String priceType,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<MaterialPriceType> pager = materialPriceTypeService.page(
        billNo, materialCode, priceType, period, current, size);
    return CommonResult.success(new MaterialPriceTypePageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增物料价格类型 */
  @PreAuthorize("@ss.hasPermi('base:material-price-type:add')")
  @PostMapping
  public CommonResult<MaterialPriceType> create(@RequestBody MaterialPriceTypeRequest request) {
    MaterialPriceType created = materialPriceTypeService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改物料价格类型 */
  @PreAuthorize("@ss.hasPermi('base:material-price-type:edit')")
  @PatchMapping("/{id}")
  public CommonResult<MaterialPriceType> update(
      @PathVariable Long id,
      @RequestBody MaterialPriceTypeRequest request) {
    MaterialPriceType updated = materialPriceTypeService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"material price type not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除物料价格类型 */
  @PreAuthorize("@ss.hasPermi('base:material-price-type:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(materialPriceTypeService.delete(id));
  }

  /** 导入物料价格类型数据 */
  @PreAuthorize("@ss.hasPermi('base:material-price-type:import')")
  @PostMapping("/import")
  public CommonResult<List<MaterialPriceType>> importItems(
      @RequestBody MaterialPriceTypeImportRequest request) {
    return CommonResult.success(materialPriceTypeService.importItems(request));
  }
}
