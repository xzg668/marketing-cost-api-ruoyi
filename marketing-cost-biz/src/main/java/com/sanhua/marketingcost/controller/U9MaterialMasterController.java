package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialRawPageResponse;
import com.sanhua.marketingcost.dto.U9MaterialTemplateMappingItem;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.BatchSummary;
import com.sanhua.marketingcost.service.U9MaterialMasterService;
import java.io.IOException;
import java.util.List;
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
@RequestMapping("/api/v1/base/u9/material-master")
public class U9MaterialMasterController {
  private static final long MAX_IMPORT_FILE_SIZE = 150L * 1024 * 1024;

  private final U9MaterialMasterService service;

  public U9MaterialMasterController(U9MaterialMasterService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('base:u9-material:list')")
  @GetMapping("/batches")
  public CommonResult<List<BatchSummary>> batches() {
    return CommonResult.success(service.listBatches());
  }

  @PreAuthorize("@ss.hasPermi('base:u9-material:import')")
  @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<U9MaterialImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      Authentication authentication) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "请上传 U9 物料主档 Excel");
    }
    if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "上传文件超过 150MB，请拆分后导入或使用离线脚本导入");
    }
    String importedBy = authentication == null ? null : authentication.getName();
    try {
      return CommonResult.success(service.importExcel(file.getInputStream(), file.getOriginalFilename(), importedBy));
    } catch (IOException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    } catch (RuntimeException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "U9 物料主档导入失败: " + e.getMessage());
    }
  }

  @PreAuthorize("@ss.hasPermi('base:u9-material:list')")
  @GetMapping("/raw")
  public CommonResult<U9MaterialRawPageResponse> raw(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String materialName,
      @RequestParam(required = false) String spec,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String drawingNo,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false) String mainCategory,
      @RequestParam(required = false) String costElement,
      @RequestParam(required = false) String bizUnit,
      @RequestParam(required = false) String dept,
      @RequestParam(required = false) String batch,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<MaterialMasterRaw> pager = service.pageRaw(
        materialCode, materialName, spec, model, drawingNo, shapeAttr, mainCategory,
        costElement, bizUnit, dept, batch, current, size);
    return CommonResult.success(new U9MaterialRawPageResponse(pager.getTotal(), pager.getRecords()));
  }

  @PreAuthorize("@ss.hasPermi('base:u9-material:export')")
  @GetMapping("/template-mapping")
  public CommonResult<List<U9MaterialTemplateMappingItem>> templateMapping() {
    return CommonResult.success(service.templateMapping());
  }
}
