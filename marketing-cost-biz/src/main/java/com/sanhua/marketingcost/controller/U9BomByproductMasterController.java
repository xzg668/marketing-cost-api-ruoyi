package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9BomByproductImportResponse;
import com.sanhua.marketingcost.dto.U9BomByproductPageResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.service.U9BomByproductMasterService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/base/u9/bom-byproduct")
public class U9BomByproductMasterController {
  private static final long MAX_IMPORT_FILE_SIZE = 30L * 1024 * 1024;

  private final U9BomByproductMasterService service;

  public U9BomByproductMasterController(U9BomByproductMasterService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('base:u9-bom-byproduct:import')")
  @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<U9BomByproductImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      Authentication authentication) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "请上传 U9 BOM副产品 Excel");
    }
    if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "上传文件超过 30MB，请拆分后导入或使用系统接口接入");
    }
    String importedBy = authentication == null ? null : authentication.getName();
    try {
      return CommonResult.success(service.importExcel(file.getInputStream(), file.getOriginalFilename(), importedBy));
    } catch (IOException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    } catch (RuntimeException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "U9 BOM副产品导入失败: " + e.getMessage());
    }
  }

  @PreAuthorize("@ss.hasPermi('base:u9-bom-byproduct:list')")
  @GetMapping("/rows")
  public CommonResult<U9BomByproductPageResponse> rows(
      @RequestParam(required = false) String parentMaterialNo,
      @RequestParam(required = false) String parentMaterialName,
      @RequestParam(required = false) String byproductMaterialNo,
      @RequestParam(required = false) String byproductMaterialName,
      @RequestParam(required = false) String bomPurpose,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<U9BomByproductMaster> pager = service.page(
        parentMaterialNo, parentMaterialName, byproductMaterialNo, byproductMaterialName,
        bomPurpose, status, asOfDate, current, size);
    return CommonResult.success(new U9BomByproductPageResponse(pager.getTotal(), pager.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('base:u9-bom-byproduct:export')")
  @GetMapping("/template-mapping")
  public CommonResult<List<U9MaterialTemplateMappingItem>> templateMapping() {
    return CommonResult.success(service.templateMapping());
  }
}
