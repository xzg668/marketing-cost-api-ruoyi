package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioService;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/supplier-supply-ratios")
public class SupplierSupplyRatioController {

  private final SupplierSupplyRatioService service;
  private final SupplierSupplyRatioImportService importService;

  public SupplierSupplyRatioController(
      SupplierSupplyRatioService service,
      SupplierSupplyRatioImportService importService) {
    this.service = service;
    this.importService = importService;
  }

  @PreAuthorize("@ss.hasPermi('base:supplier-supply-ratio:list')")
  @GetMapping
  public CommonResult<SupplierSupplyRatioPageResponse> page(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String materialName,
      @RequestParam(required = false) String specModel,
      @RequestParam(required = false) String supplierName,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return CommonResult.success(
        service.page(
            materialCode,
            materialName,
            specModel,
            supplierName,
            sourceType,
            page,
            pageSize,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('base:supplier-supply-ratio:import')")
  @PostMapping("/import-excel")
  public CommonResult<SupplierSupplyRatioImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam(required = false) String businessUnitType,
      Authentication authentication) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "请上传供应商供货比例 Excel");
    }
    try {
      return CommonResult.success(
          importService.importExcel(
              file.getInputStream(),
              file.getOriginalFilename(),
              resolveBusinessUnitType(businessUnitType),
              currentUsername(authentication)));
    } catch (IOException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return badRequest(e);
    }
  }

  @PreAuthorize("@ss.hasPermi('base:supplier-supply-ratio:edit')")
  @PatchMapping("/{id}")
  public CommonResult<SupplierSupplyRatio> update(
      @PathVariable Long id,
      @RequestBody SupplierSupplyRatioUpdateRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(service.update(id, request, currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  @PreAuthorize("@ss.hasPermi('base:supplier-supply-ratio:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Void> delete(@PathVariable Long id, Authentication authentication) {
    try {
      service.delete(id, currentUsername(authentication));
      return CommonResult.success(null);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex);
    }
  }

  private String resolveBusinessUnitType(String businessUnitType) {
    return StringUtils.hasText(businessUnitType)
        ? businessUnitType.trim()
        : BusinessUnitContext.getCurrentBusinessUnitType();
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }

  private <T> CommonResult<T> badRequest(IllegalArgumentException ex) {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
  }
}
