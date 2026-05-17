package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefPageResponse;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefImportService;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefQueryService;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/cms-cost/material-scrap-refs")
public class CmsMaterialScrapRefController {
  private final CmsMaterialScrapRefImportService importService;
  private final CmsMaterialScrapRefQueryService queryService;

  public CmsMaterialScrapRefController(
      CmsMaterialScrapRefImportService importService,
      CmsMaterialScrapRefQueryService queryService) {
    this.importService = importService;
    this.queryService = queryService;
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:import')")
  @PostMapping("/import")
  public CommonResult<CmsMaterialScrapRefImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam(name = "businessUnitType", required = false) String businessUnitType) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "请上传 CMS 原材料对应回收废料 Excel");
    }
    try {
      return CommonResult.success(
          importService.importExcel(file.getInputStream(), resolveBusinessUnitType(businessUnitType)));
    } catch (IOException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), e.getMessage());
    }
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/current")
  public CommonResult<CmsMaterialScrapRefPageResponse<MaterialScrapRef>> pageCurrent(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String scrapCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        queryService.pageCurrent(
            materialCode, scrapCode, keyword, current, size, resolveBusinessUnitType(businessUnitType)));
  }

  private String resolveBusinessUnitType(String businessUnitType) {
    return StringUtils.hasText(businessUnitType)
        ? businessUnitType.trim()
        : BusinessUnitContext.getCurrentBusinessUnitType();
  }
}
