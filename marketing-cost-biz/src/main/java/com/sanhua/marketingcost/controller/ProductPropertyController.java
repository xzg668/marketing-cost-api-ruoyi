package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.ProductPropertyImportResult;
import com.sanhua.marketingcost.dto.ProductPropertyPageResponse;
import com.sanhua.marketingcost.dto.ProductPropertyRuleSaveRequest;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import com.sanhua.marketingcost.service.ProductPropertyService;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/product-properties")
public class ProductPropertyController {
  private final ProductPropertyService productPropertyService;

  public ProductPropertyController(ProductPropertyService productPropertyService) {
    this.productPropertyService = productPropertyService;
  }

  @PreAuthorize("@ss.hasPermi('base:product-property:list')")
  @GetMapping
  public CommonResult<ProductPropertyPageResponse> list(
      @RequestParam(required = false) Integer propertyYear,
      @RequestParam(required = false) String businessDivision,
      @RequestParam(required = false) String productCode,
      @RequestParam(required = false) String productName,
      @RequestParam(required = false) String productAttr,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 200);
    Page<ProductProperty> pager = productPropertyService.page(
        propertyYear, businessDivision, productCode, productName, productAttr,
        businessUnitType, current, size);
    return CommonResult.success(new ProductPropertyPageResponse(pager.getTotal(), pager.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('base:product-property:import')")
  @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<ProductPropertyImportResult> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam Integer propertyYear,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "INCREMENTAL") String importMode)
      throws IOException {
    return CommonResult.success(productPropertyService.importExcel(
        file.getInputStream(), file.getOriginalFilename(), propertyYear, businessUnitType, importMode));
  }

  @PreAuthorize("@ss.hasPermi('base:product-property:list')")
  @GetMapping("/rules")
  public CommonResult<List<ProductPropertyRule>> listRules(
      @RequestParam Integer propertyYear,
      @RequestParam(required = false) String businessUnitType) {
    return CommonResult.success(productPropertyService.listRules(propertyYear, businessUnitType));
  }

  @PreAuthorize("@ss.hasPermi('base:product-property:edit')")
  @PutMapping("/rules")
  public CommonResult<List<ProductPropertyRule>> saveRules(
      @RequestBody ProductPropertyRuleSaveRequest request) {
    return CommonResult.success(productPropertyService.saveRules(request));
  }
}
