package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CmsCostImportRequest;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import com.sanhua.marketingcost.dto.CmsCostExcelParseError;
import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsPlanCostRaw;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsPlanCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.mapper.CmsSubjectSettingRawMapper;
import com.sanhua.marketingcost.mapper.CmsWorkshopLaborRawMapper;
import com.sanhua.marketingcost.service.CmsCostExcelParseService;
import com.sanhua.marketingcost.service.CmsCostImportService;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CmsCostImportServiceImpl implements CmsCostImportService {
  private static final String IMPORT_TYPE_EXCEL = "EXCEL";
  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_IMPORTED = "IMPORTED";
  private static final String STATUS_DRY_RUN = "DRY_RUN";

  private final CmsCostImportBatchMapper batchMapper;
  private final CmsPlanCostRawMapper planCostRawMapper;
  private final CmsWorkshopLaborRawMapper workshopLaborRawMapper;
  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final CmsSubjectSettingRawMapper subjectSettingRawMapper;
  private final CmsCostExcelParseService excelParseService;

  public CmsCostImportServiceImpl(
      CmsCostImportBatchMapper batchMapper,
      CmsPlanCostRawMapper planCostRawMapper,
      CmsWorkshopLaborRawMapper workshopLaborRawMapper,
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      CmsSubjectSettingRawMapper subjectSettingRawMapper,
      CmsCostExcelParseService excelParseService) {
    this.batchMapper = batchMapper;
    this.planCostRawMapper = planCostRawMapper;
    this.workshopLaborRawMapper = workshopLaborRawMapper;
    this.productSubjectCostRawMapper = productSubjectCostRawMapper;
    this.subjectSettingRawMapper = subjectSettingRawMapper;
    this.excelParseService = excelParseService;
  }

  @Override
  @Transactional
  public CmsCostImportResponse importExcel(
      InputStream planInput,
      String planFileName,
      InputStream workshopInput,
      String workshopFileName,
      InputStream subjectInput,
      String subjectFileName,
      InputStream subjectSettingInput,
      String subjectSettingFileName,
      boolean dryRun,
      String importedBy,
      String businessUnitType) {
    CmsCostExcelParseResult<CmsPlanCostExcelRow> planResult =
        planInput == null ? emptyParseResult() : excelParseService.parsePlanCost(planInput);
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> workshopResult =
        workshopInput == null ? emptyParseResult() : excelParseService.parseWorkshopLabor(workshopInput);
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> subjectResult =
        subjectInput == null ? emptyParseResult() : excelParseService.parseProductSubjectCost(subjectInput);
    CmsCostExcelParseResult<CmsSubjectSettingExcelRow> subjectSettingResult =
        subjectSettingInput == null
            ? emptyParseResult()
            : excelParseService.parseSubjectSetting(subjectSettingInput);
    validateParseResults(planResult, workshopResult, subjectResult, subjectSettingResult);

    if (dryRun) {
      CmsCostImportResponse response = new CmsCostImportResponse();
      response.setStatus(STATUS_DRY_RUN);
      response.setPlanRowCount(planResult.getRows().size());
      response.setWorkshopRowCount(workshopResult.getRows().size());
      response.setSubjectRowCount(subjectResult.getRows().size());
      response.setSubjectSettingRowCount(subjectSettingResult.getRows().size());
      return response;
    }

    CmsCostImportRequest request = new CmsCostImportRequest();
    request.setPlanFileName(planFileName);
    request.setWorkshopFileName(workshopFileName);
    request.setSubjectFileName(subjectFileName);
    request.setSubjectSettingFileName(subjectSettingFileName);
    request.setImportedBy(importedBy);
    request.setBusinessUnitType(businessUnitType);
    request.setPlanRows(planResult.getRows());
    request.setWorkshopRows(workshopResult.getRows());
    request.setSubjectRows(subjectResult.getRows());
    request.setSubjectSettingRows(subjectSettingResult.getRows());

    return importParsedRows(request);
  }

  private <T> CmsCostExcelParseResult<T> emptyParseResult() {
    return new CmsCostExcelParseResult<>();
  }

  @Override
  @Transactional
  public CmsCostImportResponse importParsedRows(CmsCostImportRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("CMS 导入请求不能为空");
    }
    List<CmsPlanCostExcelRow> planRows = nonNull(request.getPlanRows());
    List<CmsWorkshopLaborExcelRow> workshopRows = nonNull(request.getWorkshopRows());
    List<CmsProductSubjectCostExcelRow> subjectRows = nonNull(request.getSubjectRows());
    List<CmsSubjectSettingExcelRow> subjectSettingRows = nonNull(request.getSubjectSettingRows());

    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setBatchNo(generateBatchNo());
    batch.setImportType(IMPORT_TYPE_EXCEL);
    batch.setStatus(STATUS_PENDING);
    batch.setPlanFileName(request.getPlanFileName());
    batch.setWorkshopFileName(request.getWorkshopFileName());
    batch.setSubjectFileName(request.getSubjectFileName());
    batch.setSubjectSettingFileName(request.getSubjectSettingFileName());
    batch.setPlanRowCount(0);
    batch.setWorkshopRowCount(0);
    batch.setSubjectRowCount(0);
    batch.setSubjectSettingRowCount(0);
    batch.setSalaryInsertCount(0);
    batch.setSalarySkipCount(0);
    batch.setSalaryBlockedCount(0);
    batch.setAuxInsertCount(0);
    batch.setAuxSkipCount(0);
    batch.setErrorCount(0);
    batch.setImportedBy(request.getImportedBy());
    batch.setBusinessUnitType(request.getBusinessUnitType());
    batchMapper.insert(batch);

    for (CmsPlanCostExcelRow row : planRows) {
      upsertPlanRaw(toPlanRaw(row, batch.getId(), request.getBusinessUnitType()));
    }
    for (CmsWorkshopLaborExcelRow row : workshopRows) {
      upsertWorkshopRaw(toWorkshopRaw(row, batch.getId(), request.getBusinessUnitType()));
    }
    for (CmsProductSubjectCostExcelRow row : subjectRows) {
      upsertSubjectRaw(toSubjectRaw(row, batch.getId(), request.getBusinessUnitType()));
    }
    for (CmsSubjectSettingExcelRow row : subjectSettingRows) {
      upsertSubjectSettingRaw(
          toSubjectSettingRaw(row, batch.getId(), request.getBusinessUnitType()));
    }

    batch.setStatus(STATUS_IMPORTED);
    batch.setPlanRowCount(planRows.size());
    batch.setWorkshopRowCount(workshopRows.size());
    batch.setSubjectRowCount(subjectRows.size());
    batch.setSubjectSettingRowCount(subjectSettingRows.size());
    batchMapper.updateById(batch);

    return toResponse(batch);
  }

  private CmsPlanCostRaw toPlanRaw(CmsPlanCostExcelRow row, Long batchId, String businessUnitType) {
    CmsPlanCostRaw raw = new CmsPlanCostRaw();
    raw.setImportBatchId(batchId);
    raw.setRowNo(row.getRowNo());
    raw.setFirstUnitCode(row.getFirstUnitCode());
    raw.setFirstUnitName(row.getFirstUnitName());
    raw.setParentCode(row.getParentCode());
    raw.setParentName(row.getParentName());
    raw.setParentSpec(row.getParentSpec());
    raw.setParentType(row.getParentType());
    raw.setUnit(row.getUnit());
    raw.setWorkingHours(row.getWorkingHours());
    raw.setEffectiveDate(row.getEffectiveDate());
    raw.setEffectivePeriod(resolveEffectivePeriod(row));
    raw.setMainMaterialCost(row.getMainMaterialCost());
    raw.setAuxMaterialCost(row.getAuxMaterialCost());
    raw.setSalaryCost(row.getSalaryCost());
    raw.setFundCost(row.getFundCost());
    raw.setLossCost(row.getLossCost());
    raw.setTotalPlanCost(row.getTotalPlanCost());
    raw.setBusinessStatus(row.getBusinessStatus());
    raw.setUnapprovedItems(row.getUnapprovedItems());
    raw.setDescription(row.getDescription());
    raw.setOaNo(row.getOaNo());
    raw.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    return raw;
  }

  private CmsWorkshopLaborRaw toWorkshopRaw(
      CmsWorkshopLaborExcelRow row, Long batchId, String businessUnitType) {
    CmsWorkshopLaborRaw raw = new CmsWorkshopLaborRaw();
    raw.setImportBatchId(batchId);
    raw.setRowNo(row.getRowNo());
    raw.setPeriod(row.getPeriod());
    raw.setFirstUnitCode(row.getFirstUnitCode());
    raw.setFirstUnitName(row.getFirstUnitName());
    raw.setParentCode(row.getParentCode());
    raw.setParentName(row.getParentName());
    raw.setParentSpec(row.getParentSpec());
    raw.setParentType(row.getParentType());
    raw.setLastUnitName(row.getLastUnitName());
    raw.setLastUnitCode(row.getLastUnitCode());
    raw.setWorkingHours(row.getWorkingHours());
    raw.setFunding(row.getFunding());
    raw.setWorkingCostCent(row.getWorkingCostCent());
    raw.setWorkingCostYuan(row.getWorkingCostYuan());
    raw.setBuildFlag(row.getBuildFlag());
    raw.setPath(row.getPath());
    raw.setSourceRowId(row.getSourceRowId());
    raw.setSequenceNo(row.getSequenceNo());
    raw.setSequenceStatus(row.getSequenceStatus());
    raw.setMaterialPrice(row.getMaterialPrice());
    raw.setFirstSubjectCode(row.getFirstSubjectCode());
    raw.setFirstSubjectName(row.getFirstSubjectName());
    raw.setSecondSubjectCode(row.getSecondSubjectCode());
    raw.setSecondSubjectName(row.getSecondSubjectName());
    raw.setThirdSubjectCode(row.getThirdSubjectCode());
    raw.setThirdSubjectName(row.getThirdSubjectName());
    raw.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    return raw;
  }

  private CmsProductSubjectCostRaw toSubjectRaw(
      CmsProductSubjectCostExcelRow row, Long batchId, String businessUnitType) {
    CmsProductSubjectCostRaw raw = new CmsProductSubjectCostRaw();
    raw.setImportBatchId(batchId);
    raw.setRowNo(row.getRowNo());
    raw.setPeriod(row.getPeriod());
    raw.setFirstUnitCode(row.getFirstUnitCode());
    raw.setFirstUnitName(row.getFirstUnitName());
    raw.setParentCode(row.getParentCode());
    raw.setParentName(row.getParentName());
    raw.setParentSpec(row.getParentSpec());
    raw.setParentType(row.getParentType());
    raw.setLastSubjectCode(row.getLastSubjectCode());
    raw.setLastSubjectName(row.getLastSubjectName());
    raw.setLastSubjectLevel(row.getLastSubjectLevel());
    raw.setMaterialPrice(row.getMaterialPrice());
    raw.setMaterialPriceYuan(row.getMaterialPriceYuan());
    raw.setBuildFlag(row.getBuildFlag());
    raw.setPath(row.getPath());
    raw.setFirstSubjectCode(row.getFirstSubjectCode());
    raw.setFirstSubjectName(row.getFirstSubjectName());
    raw.setSecondSubjectCode(row.getSecondSubjectCode());
    raw.setSecondSubjectName(row.getSecondSubjectName());
    raw.setThirdSubjectCode(row.getThirdSubjectCode());
    raw.setThirdSubjectName(row.getThirdSubjectName());
    raw.setSourceRowId(row.getSourceRowId());
    raw.setSequenceNo(row.getSequenceNo());
    raw.setSequenceStatus(row.getSequenceStatus());
    raw.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    return raw;
  }

  private CmsSubjectSettingRaw toSubjectSettingRaw(
      CmsSubjectSettingExcelRow row, Long batchId, String businessUnitType) {
    CmsSubjectSettingRaw raw = new CmsSubjectSettingRaw();
    raw.setImportBatchId(batchId);
    raw.setRowNo(row.getRowNo());
    raw.setFirstSubjectCode(row.getFirstSubjectCode());
    raw.setFirstSubjectName(row.getFirstSubjectName());
    raw.setSecondSubjectCode(row.getSecondSubjectCode());
    raw.setSecondSubjectName(row.getSecondSubjectName());
    raw.setThirdSubjectCode(row.getThirdSubjectCode());
    raw.setThirdSubjectName(row.getThirdSubjectName());
    raw.setBusinessUnitType(normalizeBusinessUnit(businessUnitType));
    return raw;
  }

  private void upsertPlanRaw(CmsPlanCostRaw raw) {
    if (!StringUtils.hasText(raw.getEffectivePeriod())) {
      throw new IllegalArgumentException("产品计划成本第" + raw.getRowNo() + "行生效期间不能为空");
    }
    if (!StringUtils.hasText(raw.getParentCode())) {
      throw new IllegalArgumentException("产品计划成本第" + raw.getRowNo() + "行父件编码不能为空");
    }
    planCostRawMapper.upsert(raw);
  }

  private String resolveEffectivePeriod(CmsPlanCostExcelRow row) {
    if (StringUtils.hasText(row.getEffectivePeriod())) {
      return row.getEffectivePeriod().trim();
    }
    return row.getEffectiveDate() == null
        ? null
        : row.getEffectiveDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
  }

  private void upsertWorkshopRaw(CmsWorkshopLaborRaw raw) {
    if (!StringUtils.hasText(raw.getPeriod())) {
      throw new IllegalArgumentException("产品车间料工费第" + raw.getRowNo() + "行期间不能为空");
    }
    if (!StringUtils.hasText(raw.getParentCode())) {
      throw new IllegalArgumentException("产品车间料工费第" + raw.getRowNo() + "行父件编码不能为空");
    }
    if (!StringUtils.hasText(raw.getSourceRowId())) {
      throw new IllegalArgumentException("产品车间料工费第" + raw.getRowNo() + "行 id 不能为空");
    }
    workshopLaborRawMapper.upsert(raw);
  }

  private void upsertSubjectRaw(CmsProductSubjectCostRaw raw) {
    if (!StringUtils.hasText(raw.getPeriod())) {
      throw new IllegalArgumentException("产品科目成本第" + raw.getRowNo() + "行期间不能为空");
    }
    if (!StringUtils.hasText(raw.getParentCode())) {
      throw new IllegalArgumentException("产品科目成本第" + raw.getRowNo() + "行父件编码不能为空");
    }
    if (!StringUtils.hasText(raw.getSourceRowId())) {
      throw new IllegalArgumentException("产品科目成本第" + raw.getRowNo() + "行 id 不能为空");
    }
    productSubjectCostRawMapper.upsert(raw);
  }

  private void upsertSubjectSettingRaw(CmsSubjectSettingRaw raw) {
    if (!StringUtils.hasText(raw.getFirstSubjectCode())) {
      throw new IllegalArgumentException("CMS科目设置第" + raw.getRowNo() + "行一级科目编号不能为空");
    }
    if (!StringUtils.hasText(raw.getFirstSubjectName())) {
      throw new IllegalArgumentException("CMS科目设置第" + raw.getRowNo() + "行一级科目名称不能为空");
    }
    if (!StringUtils.hasText(raw.getSecondSubjectCode())) {
      throw new IllegalArgumentException("CMS科目设置第" + raw.getRowNo() + "行二级科目编号不能为空");
    }
    if (!StringUtils.hasText(raw.getSecondSubjectName())) {
      throw new IllegalArgumentException("CMS科目设置第" + raw.getRowNo() + "行二级科目名称不能为空");
    }
    raw.setThirdSubjectCode(normalizeNullableCode(raw.getThirdSubjectCode()));
    subjectSettingRawMapper.upsert(raw);
  }

  private String normalizeNullableCode(String code) {
    return StringUtils.hasText(code) ? code.trim() : "";
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : "";
  }

  private CmsCostImportResponse toResponse(CmsCostImportBatch batch) {
    CmsCostImportResponse response = new CmsCostImportResponse();
    response.setImportBatchId(batch.getId());
    response.setBatchNo(batch.getBatchNo());
    response.setStatus(batch.getStatus());
    response.setPlanRowCount(Objects.requireNonNullElse(batch.getPlanRowCount(), 0));
    response.setWorkshopRowCount(Objects.requireNonNullElse(batch.getWorkshopRowCount(), 0));
    response.setSubjectRowCount(Objects.requireNonNullElse(batch.getSubjectRowCount(), 0));
    response.setSubjectSettingRowCount(
        Objects.requireNonNullElse(batch.getSubjectSettingRowCount(), 0));
    response.setSalaryInsertCount(0);
    response.setSalarySkipCount(0);
    response.setSalaryBlockedCount(0);
    response.setAuxInsertCount(0);
    response.setAuxSkipCount(0);
    response.setErrorCount(0);
    return response;
  }

  private <T> List<T> nonNull(List<T> rows) {
    return rows == null ? Collections.emptyList() : rows;
  }

  private void validateParseResults(
      CmsCostExcelParseResult<CmsPlanCostExcelRow> planResult,
      CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> workshopResult,
      CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> subjectResult,
      CmsCostExcelParseResult<CmsSubjectSettingExcelRow> subjectSettingResult) {
    List<String> messages = new ArrayList<>();
    collectParseErrors(messages, "产品计划成本汇总", planResult);
    collectParseErrors(messages, "产品车间料工费汇总", workshopResult);
    collectParseErrors(messages, "产品科目成本汇总", subjectResult);
    collectParseErrors(messages, "科目设置", subjectSettingResult);
    if (!messages.isEmpty()) {
      throw new IllegalArgumentException("CMS Excel 解析失败: " + String.join("; ", messages));
    }
  }

  private void collectParseErrors(
      List<String> messages, String fileLabel, CmsCostExcelParseResult<?> result) {
    if (result == null || !result.hasErrors()) {
      return;
    }
    for (CmsCostExcelParseError error : result.getErrors()) {
      StringBuilder message = new StringBuilder(fileLabel);
      if (error.getRowNo() != null) {
        message.append(" 第").append(error.getRowNo()).append("行");
      }
      if (error.getColumnName() != null) {
        message.append("[").append(error.getColumnName()).append("]");
      }
      message.append(error.getMessage());
      messages.add(message.toString());
    }
  }

  private String generateBatchNo() {
    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return "CMS" + timestamp + suffix;
  }
}
