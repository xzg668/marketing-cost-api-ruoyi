package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ProductPropertyImportRequest;
import com.sanhua.marketingcost.dto.ProductPropertyPageResponse;
import com.sanhua.marketingcost.dto.ProductPropertyRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.service.ProductPropertyService;
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
 * 产品属性控制器 - 管理产品属性的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/product-properties")
public class ProductPropertyController {
  private final ProductPropertyService productPropertyService;

  public ProductPropertyController(ProductPropertyService productPropertyService) {
    this.productPropertyService = productPropertyService;
  }

  /** 查询产品属性列表 */
  @PreAuthorize("@ss.hasPermi('base:product-property:list')")
  @GetMapping
  public CommonResult<ProductPropertyPageResponse> list(
      @RequestParam(required = false) String level1Name,
      @RequestParam(required = false) String parentCode) {
    List<ProductProperty> list = productPropertyService.list(level1Name, parentCode);
    return CommonResult.success(new ProductPropertyPageResponse(list.size(), list));
  }

  /** 新增产品属性 */
  @PreAuthorize("@ss.hasPermi('base:product-property:add')")
  @PostMapping
  public CommonResult<ProductProperty> create(@RequestBody ProductPropertyRequest request) {
    ProductProperty created = productPropertyService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改产品属性 */
  @PreAuthorize("@ss.hasPermi('base:product-property:edit')")
  @PatchMapping("/{id}")
  public CommonResult<ProductProperty> update(
      @PathVariable Long id,
      @RequestBody ProductPropertyRequest request) {
    ProductProperty updated = productPropertyService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"product property not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除产品属性 */
  @PreAuthorize("@ss.hasPermi('base:product-property:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(productPropertyService.delete(id));
  }

  /** 导入产品属性数据 */
  @PreAuthorize("@ss.hasPermi('base:product-property:import')")
  @PostMapping("/import")
  public CommonResult<List<ProductProperty>> importItems(
      @RequestBody ProductPropertyImportRequest request) {
    return CommonResult.success(productPropertyService.importItems(request));
  }
}
