package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CmsCostExcelParseError;
import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefExcelRow;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefSourceRow;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import com.sanhua.marketingcost.service.CmsCostExcelParseService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CmsCostExcelParseServiceImpl implements CmsCostExcelParseService {
  private static final int PLAN_HEADER_ROW = 0;
  private static final int PLAN_DATA_START_ROW = 1;
  private static final int CMS_EXPORT_EN_HEADER_ROW = 0;
  private static final int CMS_EXPORT_DATA_START_ROW = 3;
  private static final BigDecimal CENT_TO_YUAN = new BigDecimal("100");
  private static final List<DateTimeFormatter> DATE_FORMATTERS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("yyyy/M/d"),
          DateTimeFormatter.ofPattern("yyyy.M.d"),
          DateTimeFormatter.ofPattern("yyyyMMdd"));

  @Override
  public CmsCostExcelParseResult<CmsPlanCostExcelRow> parsePlanCost(InputStream input) {
    CmsCostExcelParseResult<CmsPlanCostExcelRow> result = new CmsCostExcelParseResult<>();
    try (Workbook workbook = openWorkbook(input, result)) {
      if (workbook == null) {
        return result;
      }
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      Map<String, Integer> columns = readColumns(sheet.getRow(PLAN_HEADER_ROW), formatter, evaluator);
      requireColumns(
          result,
          columns,
          "一级编码",
          "一级编码名称",
          "父件编码",
          "父件名称",
          "父件规格",
          "父件型号",
          "单位",
          "工时",
          "生效时间",
          "主材成本",
          "辅材成本",
          "工资成本",
          "经费成本",
          "损失成本",
          "计划价(总)",
          "业务状态",
          "未审批项",
          "制定说明",
          "OA单号");
      for (int rowIndex = PLAN_DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row, formatter, evaluator)) {
          continue;
        }
        int rowNo = rowIndex + 1;
        String parentCode = cell(row, columns, "父件编码", formatter, evaluator);
        if (!StringUtils.hasText(parentCode)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "父件编码", "父件编码不能为空"));
          continue;
        }
        CmsPlanCostExcelRow parsed = new CmsPlanCostExcelRow();
        parsed.setRowNo(rowNo);
        parsed.setFirstUnitCode(cell(row, columns, "一级编码", formatter, evaluator));
        parsed.setFirstUnitName(cell(row, columns, "一级编码名称", formatter, evaluator));
        parsed.setParentCode(parentCode);
        parsed.setParentName(cell(row, columns, "父件名称", formatter, evaluator));
        parsed.setParentSpec(cell(row, columns, "父件规格", formatter, evaluator));
        parsed.setParentType(cell(row, columns, "父件型号", formatter, evaluator));
        parsed.setUnit(cell(row, columns, "单位", formatter, evaluator));
        parsed.setWorkingHours(decimal(row, columns, "工时", formatter, evaluator, result, rowNo));
        LocalDate effectiveDate = date(row, columns, "生效时间", formatter, evaluator, result, rowNo);
        parsed.setEffectiveDate(effectiveDate);
        parsed.setEffectivePeriod(effectiveDate == null ? null : effectiveDate.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        parsed.setMainMaterialCost(decimal(row, columns, "主材成本", formatter, evaluator, result, rowNo));
        parsed.setAuxMaterialCost(decimal(row, columns, "辅材成本", formatter, evaluator, result, rowNo));
        parsed.setSalaryCost(decimal(row, columns, "工资成本", formatter, evaluator, result, rowNo));
        parsed.setFundCost(decimal(row, columns, "经费成本", formatter, evaluator, result, rowNo));
        parsed.setLossCost(decimal(row, columns, "损失成本", formatter, evaluator, result, rowNo));
        parsed.setTotalPlanCost(decimal(row, columns, "计划价(总)", formatter, evaluator, result, rowNo));
        parsed.setBusinessStatus(cell(row, columns, "业务状态", formatter, evaluator));
        parsed.setUnapprovedItems(cell(row, columns, "未审批项", formatter, evaluator));
        parsed.setDescription(cell(row, columns, "制定说明", formatter, evaluator));
        parsed.setOaNo(cell(row, columns, "OA单号", formatter, evaluator));
        result.getRows().add(parsed);
      }
    } catch (IOException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 关闭失败: " + ex.getMessage()));
    }
    return result;
  }

  @Override
  public CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> parseWorkshopLabor(InputStream input) {
    CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> result = new CmsCostExcelParseResult<>();
    try (Workbook workbook = openWorkbook(input, result)) {
      if (workbook == null) {
        return result;
      }
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      Map<String, Integer> columns =
          readColumns(sheet.getRow(CMS_EXPORT_EN_HEADER_ROW), formatter, evaluator);
      requireColumns(result, columns, "period", "parentCode", "workingCost", "id");
      for (int rowIndex = CMS_EXPORT_DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row, formatter, evaluator)) {
          continue;
        }
        int rowNo = rowIndex + 1;
        String parentCode = cell(row, columns, "parentCode", formatter, evaluator);
        String period = period(row, columns, "period", formatter, evaluator, result, rowNo);
        if (!StringUtils.hasText(parentCode)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "parentCode", "父件编码不能为空"));
          continue;
        }
        if (!StringUtils.hasText(period)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "period", "期间不能为空或格式不正确"));
          continue;
        }
        String sourceRowId = cell(row, columns, "id", formatter, evaluator);
        if (!StringUtils.hasText(sourceRowId)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "id", "CMS原始id不能为空"));
          continue;
        }
        CmsWorkshopLaborExcelRow parsed = new CmsWorkshopLaborExcelRow();
        parsed.setRowNo(rowNo);
        parsed.setPeriod(period);
        parsed.setFirstUnitCode(cell(row, columns, "firstUnitCode", formatter, evaluator));
        parsed.setFirstUnitName(cell(row, columns, "firstUnitName", formatter, evaluator));
        parsed.setParentCode(parentCode);
        parsed.setParentName(cell(row, columns, "parentName", formatter, evaluator));
        parsed.setParentSpec(cell(row, columns, "parentSpec", formatter, evaluator));
        parsed.setParentType(cell(row, columns, "parentType", formatter, evaluator));
        parsed.setLastUnitName(cell(row, columns, "lastUnitName", formatter, evaluator));
        parsed.setLastUnitCode(cell(row, columns, "lastUnitCode", formatter, evaluator));
        parsed.setWorkingHours(decimal(row, columns, "workingHours", formatter, evaluator, result, rowNo));
        parsed.setFunding(decimal(row, columns, "funding", formatter, evaluator, result, rowNo));
        BigDecimal workingCostCent =
            decimal(row, columns, "workingCost", formatter, evaluator, result, rowNo);
        parsed.setWorkingCostCent(workingCostCent);
        parsed.setWorkingCostYuan(toYuan(workingCostCent));
        parsed.setBuildFlag(cell(row, columns, "buildFlag", formatter, evaluator));
        parsed.setPath(cell(row, columns, "path", formatter, evaluator));
        parsed.setSourceRowId(sourceRowId);
        parsed.setSequenceNo(cell(row, columns, "sequenceNo", formatter, evaluator));
        parsed.setSequenceStatus(cell(row, columns, "sequenceStatus", formatter, evaluator));
        BigDecimal materialPriceCent =
            decimal(row, columns, "materialPrice", formatter, evaluator, result, rowNo);
        parsed.setMaterialPrice(materialPriceCent);
        parsed.setMaterialPriceYuan(toYuan(materialPriceCent));
        parsed.setFirstSubjectCode(cell(row, columns, "firstSubjectCode", formatter, evaluator));
        parsed.setFirstSubjectName(cell(row, columns, "firstSubjectName", formatter, evaluator));
        parsed.setSecondSubjectCode(cell(row, columns, "secondSubjectCode", formatter, evaluator));
        parsed.setSecondSubjectName(cell(row, columns, "secondSubjectName", formatter, evaluator));
        parsed.setThirdSubjectCode(cell(row, columns, "thirdSubjectCode", formatter, evaluator));
        parsed.setThirdSubjectName(cell(row, columns, "thirdSubjectName", formatter, evaluator));
        result.getRows().add(parsed);
      }
    } catch (IOException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 关闭失败: " + ex.getMessage()));
    }
    return result;
  }

  @Override
  public CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> parseProductSubjectCost(
      InputStream input) {
    CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> result = new CmsCostExcelParseResult<>();
    try (Workbook workbook = openWorkbook(input, result)) {
      if (workbook == null) {
        return result;
      }
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      Map<String, Integer> columns =
          readColumns(sheet.getRow(CMS_EXPORT_EN_HEADER_ROW), formatter, evaluator);
      requireColumns(result, columns, "period", "parentCode", "materialPrice", "firstSubjectName", "secondSubjectName", "id");
      for (int rowIndex = CMS_EXPORT_DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row, formatter, evaluator)) {
          continue;
        }
        int rowNo = rowIndex + 1;
        String parentCode = cell(row, columns, "parentCode", formatter, evaluator);
        String period = period(row, columns, "period", formatter, evaluator, result, rowNo);
        if (!StringUtils.hasText(parentCode)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "parentCode", "父件编码不能为空"));
          continue;
        }
        if (!StringUtils.hasText(period)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "period", "期间不能为空或格式不正确"));
          continue;
        }
        String sourceRowId = cell(row, columns, "id", formatter, evaluator);
        if (!StringUtils.hasText(sourceRowId)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "id", "CMS原始id不能为空"));
          continue;
        }
        CmsProductSubjectCostExcelRow parsed = new CmsProductSubjectCostExcelRow();
        parsed.setRowNo(rowNo);
        parsed.setPeriod(period);
        parsed.setFirstUnitCode(cell(row, columns, "firstUnitCode", formatter, evaluator));
        parsed.setFirstUnitName(cell(row, columns, "firstUnitName", formatter, evaluator));
        parsed.setParentCode(parentCode);
        parsed.setParentName(cell(row, columns, "parentName", formatter, evaluator));
        parsed.setParentSpec(cell(row, columns, "parentSpec", formatter, evaluator));
        parsed.setParentType(cell(row, columns, "parentType", formatter, evaluator));
        parsed.setLastSubjectCode(cell(row, columns, "lastSubjectCode", formatter, evaluator));
        parsed.setLastSubjectName(cell(row, columns, "lastSubjectName", formatter, evaluator));
        parsed.setLastSubjectLevel(cell(row, columns, "lastSubjectLevel", formatter, evaluator));
        BigDecimal materialPriceCent =
            decimal(row, columns, "materialPrice", formatter, evaluator, result, rowNo);
        parsed.setMaterialPrice(materialPriceCent);
        parsed.setMaterialPriceYuan(toYuan(materialPriceCent));
        parsed.setBuildFlag(cell(row, columns, "buildFlag", formatter, evaluator));
        parsed.setPath(cell(row, columns, "path", formatter, evaluator));
        parsed.setFirstSubjectCode(cell(row, columns, "firstSubjectCode", formatter, evaluator));
        parsed.setFirstSubjectName(cell(row, columns, "firstSubjectName", formatter, evaluator));
        parsed.setSecondSubjectCode(cell(row, columns, "secondSubjectCode", formatter, evaluator));
        parsed.setSecondSubjectName(cell(row, columns, "secondSubjectName", formatter, evaluator));
        parsed.setThirdSubjectCode(cell(row, columns, "thirdSubjectCode", formatter, evaluator));
        parsed.setThirdSubjectName(cell(row, columns, "thirdSubjectName", formatter, evaluator));
        parsed.setSourceRowId(sourceRowId);
        parsed.setSequenceNo(cell(row, columns, "sequenceNo", formatter, evaluator));
        parsed.setSequenceStatus(cell(row, columns, "sequenceStatus", formatter, evaluator));
        result.getRows().add(parsed);
      }
    } catch (IOException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 关闭失败: " + ex.getMessage()));
    }
    return result;
  }

  @Override
  public CmsCostExcelParseResult<CmsSubjectSettingExcelRow> parseSubjectSetting(InputStream input) {
    CmsCostExcelParseResult<CmsSubjectSettingExcelRow> result = new CmsCostExcelParseResult<>();
    try (Workbook workbook = openWorkbook(input, result)) {
      if (workbook == null) {
        return result;
      }
      Sheet sheet = workbook.getSheetAt(0);
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      Map<String, Integer> columns =
          readColumns(sheet.getRow(CMS_EXPORT_EN_HEADER_ROW), formatter, evaluator);
      requireColumns(
          result,
          columns,
          "firstLevelSubjectCode",
          "firstLevelSubjectName",
          "secondLevelSubjectCode",
          "secondLevelSubjectName");
      for (int rowIndex = CMS_EXPORT_DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row, formatter, evaluator)) {
          continue;
        }
        int rowNo = rowIndex + 1;
        String firstCode = cell(row, columns, "firstLevelSubjectCode", formatter, evaluator);
        String firstName = cell(row, columns, "firstLevelSubjectName", formatter, evaluator);
        String secondCode = cell(row, columns, "secondLevelSubjectCode", formatter, evaluator);
        String secondName = cell(row, columns, "secondLevelSubjectName", formatter, evaluator);
        if (!StringUtils.hasText(firstCode)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "firstLevelSubjectCode", "一级科目编号不能为空"));
          continue;
        }
        if (!StringUtils.hasText(firstName)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "firstLevelSubjectName", "一级科目名称不能为空"));
          continue;
        }
        if (!StringUtils.hasText(secondCode)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "secondLevelSubjectCode", "二级科目编号不能为空"));
          continue;
        }
        if (!StringUtils.hasText(secondName)) {
          result.getErrors().add(new CmsCostExcelParseError(rowNo, "secondLevelSubjectName", "二级科目名称不能为空"));
          continue;
        }
        CmsSubjectSettingExcelRow parsed = new CmsSubjectSettingExcelRow();
        parsed.setRowNo(rowNo);
        parsed.setFirstSubjectCode(firstCode);
        parsed.setFirstSubjectName(firstName);
        parsed.setSecondSubjectCode(secondCode);
        parsed.setSecondSubjectName(secondName);
        parsed.setThirdSubjectCode(firstExistingCell(row, columns, formatter, evaluator, "thirdLevelSubjectCode", "thiedLevelSubjectCode"));
        parsed.setThirdSubjectName(cell(row, columns, "thirdLevelSubjectName", formatter, evaluator));
        result.getRows().add(parsed);
      }
    } catch (IOException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 关闭失败: " + ex.getMessage()));
    }
    return result;
  }

  @Override
  public CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> parseMaterialScrapRef(
      InputStream input) {
    CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> result = new CmsCostExcelParseResult<>();
    try (Workbook workbook = openWorkbook(input, result)) {
      if (workbook == null) {
        return result;
      }
      Sheet sheet = getSheet0(workbook);
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      Map<String, Integer> columns =
          readColumns(sheet.getRow(CMS_EXPORT_EN_HEADER_ROW), formatter, evaluator);
      requireColumns(result, columns, "materialCode", "recycleMaterialCode");
      if (result.hasErrors()) {
        return result;
      }
      for (int rowIndex = CMS_EXPORT_DATA_START_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (isBlankRow(row, formatter, evaluator)) {
          continue;
        }
        String materialCode = cell(row, columns, "materialCode", formatter, evaluator);
        String recycleMaterialCode = cell(row, columns, "recycleMaterialCode", formatter, evaluator);
        if (CmsFieldNormalizeUtils.isChineseMaterialScrapHeader(materialCode)) {
          continue;
        }
        if (!StringUtils.hasText(CmsFieldNormalizeUtils.normalize(materialCode))
            && !StringUtils.hasText(CmsFieldNormalizeUtils.normalize(recycleMaterialCode))) {
          continue;
        }
        int rowNo = rowIndex + 1;
        CmsMaterialScrapRefExcelRow parsed = new CmsMaterialScrapRefExcelRow();
        parsed.setRowNo(rowNo);
        parsed.setMaterialCode(materialCode);
        parsed.setMaterialName(cell(row, columns, "materialName", formatter, evaluator));
        parsed.setMaterialSpecifications(
            cell(row, columns, "materialSpecifications", formatter, evaluator));
        parsed.setMaterialModel(cell(row, columns, "materialModel", formatter, evaluator));
        parsed.setMaterialUnit(cell(row, columns, "materialUnit", formatter, evaluator));
        parsed.setRecycleMaterialCode(recycleMaterialCode);
        parsed.setRecycleMaterialName(cell(row, columns, "recycleMaterialName", formatter, evaluator));
        parsed.setRecycleMaterialSpecification(
            cell(row, columns, "recycleMaterialSpecification", formatter, evaluator));
        parsed.setRecycleMaterialModel(
            cell(row, columns, "recycleMaterialModel", formatter, evaluator));
        parsed.setRecycleMaterialUnit(cell(row, columns, "recycleMaterialUnit", formatter, evaluator));
        parsed.setRecycleMaterialInfoVersion(
            firstExistingCell(
                row,
                columns,
                formatter,
                evaluator,
                "RecycleMaterialInfoVersion",
                "recycleMaterialInfoVersion"));
        parsed.setCostGroupName(cell(row, columns, "costGroupName", formatter, evaluator));
        parsed.setSequenceNo(cell(row, columns, "sequenceNo", formatter, evaluator));
        parsed.setId(cell(row, columns, "id", formatter, evaluator));
        parsed.setSequenceStatus(cell(row, columns, "sequenceStatus", formatter, evaluator));
        parsed.setLinkDetailId(cell(row, columns, "linkDetailId", formatter, evaluator));
        parsed.setSyncTime(date(row, columns, "syncTime", formatter, evaluator, result, rowNo));
        parsed.setApprovalTime(date(row, columns, "approvalTime", formatter, evaluator, result, rowNo));
        parsed.setCostGroupCode(cell(row, columns, "costGroupCode", formatter, evaluator));
        parsed.setEffectiveDate(date(row, columns, "effectiveDate", formatter, evaluator, result, rowNo));
        parsed.setPostingPeriod(
            period(row, columns, "postingPeriod", formatter, evaluator, result, rowNo));
        result.getRows().add(parsed.toSourceRow());
      }
    } catch (IOException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 关闭失败: " + ex.getMessage()));
    }
    return result;
  }

  private <T> Workbook openWorkbook(InputStream input, CmsCostExcelParseResult<T> result) {
    if (input == null) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 流为空"));
      return null;
    }
    try {
      return WorkbookFactory.create(input);
    } catch (IOException | RuntimeException ex) {
      result.getErrors().add(new CmsCostExcelParseError(null, null, "Excel 解析失败: " + ex.getMessage()));
      return null;
    }
  }

  private Map<String, Integer> readColumns(
      Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    if (row == null) {
      return columns;
    }
    for (Cell cell : row) {
      String value = cellValue(cell, formatter, evaluator);
      if (StringUtils.hasText(value)) {
        columns.put(value.trim(), cell.getColumnIndex());
      }
    }
    return columns;
  }

  private Sheet getSheet0(Workbook workbook) {
    Sheet sheet0 = workbook.getSheet("Sheet0");
    return sheet0 == null ? workbook.getSheetAt(0) : sheet0;
  }

  private <T> void requireColumns(
      CmsCostExcelParseResult<T> result, Map<String, Integer> columns, String... requiredColumns) {
    for (String column : requiredColumns) {
      if (!columns.containsKey(column)) {
        result.getErrors().add(new CmsCostExcelParseError(1, column, "缺少必要列"));
      }
    }
  }

  private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (row == null) {
      return true;
    }
    for (Cell cell : row) {
      if (StringUtils.hasText(cellValue(cell, formatter, evaluator))) {
        return false;
      }
    }
    return true;
  }

  private String cell(
      Row row,
      Map<String, Integer> columns,
      String columnName,
      DataFormatter formatter,
      FormulaEvaluator evaluator) {
    Integer column = columns.get(columnName);
    if (row == null || column == null) {
      return null;
    }
    return cellValue(row.getCell(column), formatter, evaluator);
  }

  private String firstExistingCell(
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      String... columnNames) {
    for (String columnName : columnNames) {
      String value = cell(row, columns, columnName, formatter, evaluator);
      if (StringUtils.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (cell == null) {
      return null;
    }
    String value = formatter.formatCellValue(cell, evaluator);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private <T> BigDecimal decimal(
      Row row,
      Map<String, Integer> columns,
      String columnName,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      CmsCostExcelParseResult<T> result,
      int rowNo) {
    String value = cell(row, columns, columnName, formatter, evaluator);
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return new BigDecimal(value.replace(",", "").trim());
    } catch (NumberFormatException ex) {
      result.getErrors().add(new CmsCostExcelParseError(rowNo, columnName, "数字格式不正确: " + value));
      return null;
    }
  }

  private BigDecimal toYuan(BigDecimal cent) {
    if (cent == null) {
      return null;
    }
    return cent.divide(CENT_TO_YUAN, 6, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  private <T> LocalDate date(
      Row row,
      Map<String, Integer> columns,
      String columnName,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      CmsCostExcelParseResult<T> result,
      int rowNo) {
    Integer column = columns.get(columnName);
    Cell cell = row == null || column == null ? null : row.getCell(column);
    if (cell == null) {
      return null;
    }
    if (isDateCell(cell, evaluator)) {
      return DateUtil.getJavaDate(numericValue(cell, evaluator))
          .toInstant()
          .atZone(ZoneId.systemDefault())
          .toLocalDate();
    }
    String value = cellValue(cell, formatter, evaluator);
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() >= 10 && (normalized.charAt(4) == '-' || normalized.charAt(4) == '/' || normalized.charAt(4) == '.')) {
      normalized = normalized.substring(0, 10);
    }
    for (DateTimeFormatter dateFormatter : DATE_FORMATTERS) {
      try {
        return LocalDate.parse(normalized, dateFormatter);
      } catch (DateTimeParseException ignored) {
        // Try the next supported CMS date format.
      }
    }
    result.getErrors().add(new CmsCostExcelParseError(rowNo, columnName, "日期格式不正确: " + value));
    return null;
  }

  private <T> String period(
      Row row,
      Map<String, Integer> columns,
      String columnName,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      CmsCostExcelParseResult<T> result,
      int rowNo) {
    Integer column = columns.get(columnName);
    Cell cell = row == null || column == null ? null : row.getCell(column);
    if (cell == null) {
      return null;
    }
    if (isDateCell(cell, evaluator)) {
      LocalDate date =
          DateUtil.getJavaDate(numericValue(cell, evaluator))
              .toInstant()
              .atZone(ZoneId.systemDefault())
              .toLocalDate();
      return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
    String value = cellValue(cell, formatter, evaluator);
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim().replace("/", "-").replace(".", "-");
    if (normalized.matches("\\d{4}-\\d{2}.*")) {
      return normalized.substring(0, 7);
    }
    if (normalized.matches("\\d{6}")) {
      return normalized.substring(0, 4) + "-" + normalized.substring(4, 6);
    }
    result.getErrors().add(new CmsCostExcelParseError(rowNo, columnName, "期间格式不正确: " + value));
    return null;
  }

  private boolean isDateCell(Cell cell, FormulaEvaluator evaluator) {
    if (cell.getCellType() == CellType.NUMERIC) {
      return DateUtil.isCellDateFormatted(cell);
    }
    if (cell.getCellType() == CellType.FORMULA) {
      return evaluator.evaluateFormulaCell(cell) == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell);
    }
    return false;
  }

  private double numericValue(Cell cell, FormulaEvaluator evaluator) {
    if (cell.getCellType() == CellType.FORMULA) {
      return evaluator.evaluate(cell).getNumberValue();
    }
    return cell.getNumericCellValue();
  }
}
