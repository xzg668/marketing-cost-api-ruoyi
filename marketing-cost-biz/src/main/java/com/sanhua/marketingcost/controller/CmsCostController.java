package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CmsCostBatchPageResponse;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsCostRawPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectiveLogPageResponse;
import com.sanhua.marketingcost.dto.CmsCostSourceEffectivePageResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceRefreshRequest;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsCostImportService;
import com.sanhua.marketingcost.service.CmsCostQueryService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/v1/cms-cost")
public class CmsCostController {

  private final CmsCostImportService cmsCostImportService;
  private final CmsCostQueryService cmsCostQueryService;
  private final CmsSalaryCostSourceEffectiveService salaryEffectiveService;
  private final CmsAuxSubjectSourceEffectiveService auxEffectiveService;

  public CmsCostController(
      CmsCostImportService cmsCostImportService,
      CmsCostQueryService cmsCostQueryService,
      CmsSalaryCostSourceEffectiveService salaryEffectiveService,
      CmsAuxSubjectSourceEffectiveService auxEffectiveService) {
    this.cmsCostImportService = cmsCostImportService;
    this.cmsCostQueryService = cmsCostQueryService;
    this.salaryEffectiveService = salaryEffectiveService;
    this.auxEffectiveService = auxEffectiveService;
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:import')")
  @PostMapping("/import")
  public CommonResult<CmsCostImportResponse> importCmsCost(
      @RequestPart(name = "planFile", required = false) MultipartFile planFile,
      @RequestPart(name = "workshopFile", required = false) MultipartFile workshopFile,
      @RequestPart(name = "subjectFile", required = false) MultipartFile subjectFile,
      @RequestPart(name = "subjectSettingFile", required = false) MultipartFile subjectSettingFile,
      @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
      @RequestParam(name = "businessUnitType", required = false) String businessUnitType,
      Authentication auth) {
    if (!hasUploadedFile(planFile, workshopFile, subjectFile, subjectSettingFile)) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "至少需要上传一个 CMS Excel 文件");
    }
    String importedBy = auth == null ? null : auth.getName();
    String resolvedBusinessUnitType = resolveBusinessUnitType(businessUnitType);
    try {
      return CommonResult.success(
          cmsCostImportService.importExcel(
              inputStreamOrNull(planFile),
              originalFilenameOrNull(planFile),
              inputStreamOrNull(workshopFile),
              originalFilenameOrNull(workshopFile),
              inputStreamOrNull(subjectFile),
              originalFilenameOrNull(subjectFile),
              inputStreamOrNull(subjectSettingFile),
              originalFilenameOrNull(subjectSettingFile),
              dryRun,
              importedBy,
              resolvedBusinessUnitType));
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "读取上传文件失败: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), e.getMessage());
    }
  }

  private String resolveBusinessUnitType(String businessUnitType) {
    return StringUtils.hasText(businessUnitType)
        ? businessUnitType.trim()
        : BusinessUnitContext.getCurrentBusinessUnitType();
  }

  private boolean hasUploadedFile(
      MultipartFile planFile,
      MultipartFile workshopFile,
      MultipartFile subjectFile,
      MultipartFile subjectSettingFile) {
    return hasContent(planFile)
        || hasContent(workshopFile)
        || hasContent(subjectFile)
        || hasContent(subjectSettingFile);
  }

  private boolean hasContent(MultipartFile file) {
    return file != null && !file.isEmpty();
  }

  private InputStream inputStreamOrNull(MultipartFile file) throws IOException {
    return hasContent(file) ? file.getInputStream() : null;
  }

  private String originalFilenameOrNull(MultipartFile file) {
    return hasContent(file) ? file.getOriginalFilename() : null;
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/batches")
  public CommonResult<CmsCostBatchPageResponse> pageBatches(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageBatches(
            batchNo, status, current, size, resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/import-records")
  public CommonResult<CmsCostBatchPageResponse> pageImportRecords(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return pageBatches(batchNo, status, businessUnitType, current, size);
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/plan-rows")
  public CommonResult<CmsCostRawPageResponse<CmsPlanCostRaw>> pagePlanRows(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer costYear,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pagePlanRows(
            batchNo,
            parentCode,
            costYear,
            period,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/workshop-rows")
  public CommonResult<CmsCostRawPageResponse<CmsWorkshopLaborRaw>> pageWorkshopRows(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer costYear,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageWorkshopRows(
            batchNo,
            parentCode,
            costYear,
            period,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/subject-rows")
  public CommonResult<CmsCostRawPageResponse<CmsProductSubjectCostRaw>> pageSubjectRows(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer costYear,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String subjectName,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageSubjectRows(
            batchNo,
            parentCode,
            costYear,
            period,
            subjectCode,
            subjectName,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/subject-settings")
  public CommonResult<CmsCostRawPageResponse<CmsSubjectSettingRaw>> pageSubjectSettings(
      @RequestParam(required = false) String batchNo,
      @RequestParam(required = false) String firstSubjectName,
      @RequestParam(required = false) String secondSubjectCode,
      @RequestParam(required = false) String secondSubjectName,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageSubjectSettings(
            batchNo,
            firstSubjectName,
            secondSubjectCode,
            secondSubjectName,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/effective-sources")
  public CommonResult<CmsCostSourceEffectivePageResponse> pageEffectiveSources(
      @RequestParam(required = false) Integer costYear,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageEffectiveSources(
            costYear,
            parentCode,
            period,
            sourceType,
            subjectCode,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:list')")
  @GetMapping("/effective-source-logs")
  public CommonResult<CmsCostSourceEffectiveLogPageResponse> pageEffectiveSourceLogs(
      @RequestParam(required = false) Integer costYear,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String subjectCode,
      @RequestParam(required = false) String actionType,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        cmsCostQueryService.pageEffectiveSourceLogs(
            costYear,
            parentCode,
            period,
            sourceType,
            subjectCode,
            actionType,
            current,
            size,
            resolveBusinessUnitType(businessUnitType)));
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:effective:refresh')")
  @PostMapping("/effective-sources/generate-default")
  public CommonResult<CmsEffectiveSourceGenerateResponse> generateDefaultSources(
      @RequestParam Integer costYear, Authentication auth) {
    String operator = auth == null ? null : auth.getName();
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    CmsEffectiveSourceGenerateResponse salary =
        salaryEffectiveService.generateDefaultSources(costYear, operator, businessUnitType);
    CmsEffectiveSourceGenerateResponse aux =
        auxEffectiveService.generateDefaultSources(costYear, operator, businessUnitType);
    CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
    response.setCostYear(costYear);
    response.setInsertedCount(salary.getInsertedCount() + aux.getInsertedCount());
    response.setUpdatedCount(salary.getUpdatedCount() + aux.getUpdatedCount());
    response.setSkippedCount(salary.getSkippedCount() + aux.getSkippedCount());
    response.setBlockedCount(salary.getBlockedCount() + aux.getBlockedCount());
    response.setErrorCount(salary.getErrorCount() + aux.getErrorCount());
    return CommonResult.success(response);
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:effective:refresh')")
  @PostMapping("/effective-sources/refresh")
  public CommonResult<?> refreshEffectiveSource(
      @RequestBody CmsEffectiveSourceRefreshRequest request, Authentication auth) {
    String operator = auth == null ? null : auth.getName();
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    try {
      if (request == null) {
        throw new IllegalArgumentException("刷新请求不能为空");
      }
      if (!StringUtils.hasText(request.getSourceType())) {
        CmsEffectiveSourceGenerateResponse salary =
            salaryEffectiveService.refreshParentPeriod(request, operator, businessUnitType);
        CmsEffectiveSourceGenerateResponse aux =
            auxEffectiveService.refreshParentPeriod(request, operator, businessUnitType);
        CmsEffectiveSourceGenerateResponse response = new CmsEffectiveSourceGenerateResponse();
        response.setCostYear(request.getCostYear());
        response.setInsertedCount(salary.getInsertedCount() + aux.getInsertedCount());
        response.setUpdatedCount(salary.getUpdatedCount() + aux.getUpdatedCount());
        response.setSkippedCount(salary.getSkippedCount() + aux.getSkippedCount());
        response.setBlockedCount(salary.getBlockedCount() + aux.getBlockedCount());
        response.setErrorCount(salary.getErrorCount() + aux.getErrorCount());
        return CommonResult.success(response);
      }
      if ("AUX_SUBJECT".equals(request.getSourceType())) {
        return CommonResult.success(auxEffectiveService.refreshSource(request, operator, businessUnitType));
      }
      return CommonResult.success(salaryEffectiveService.refreshSource(request, operator, businessUnitType));
    } catch (IllegalArgumentException e) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), e.getMessage());
    }
  }
}
