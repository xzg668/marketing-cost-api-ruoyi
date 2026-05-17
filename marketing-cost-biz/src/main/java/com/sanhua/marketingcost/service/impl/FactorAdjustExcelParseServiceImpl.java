package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseRow;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.enums.FactorAdjustMatchMethod;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorAdjustExcelParseService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FactorAdjustExcelParseServiceImpl implements FactorAdjustExcelParseService {

  private static final String STATUS_MATCHED = "MATCHED";
  private static final String STATUS_FAILED = "FAILED";

  private static final String HEADER_SEQ = "序号";
  private static final String HEADER_FACTOR_NAME = "价表影响因素名称";
  private static final String HEADER_SHORT_NAME = "简称";
  private static final String HEADER_PRICE_SOURCE = "取价来源";
  private static final String HEADER_PRICE = "价格";
  private static final String HEADER_ORIGINAL_PRICE = "原价";
  private static final String HEADER_ORIGINAL_PRICE_LEGACY = "价格-原价";
  private static final String HEADER_UNIT = "单位";
  private static final String HEADER_FACTOR_ID = "影响因素ID";
  private static final String HEADER_FACTOR_MONTHLY_PRICE_ID = "月度价格ID";

  private static final Set<String> REQUIRED_HEADERS =
      Set.of(HEADER_SEQ, HEADER_FACTOR_NAME, HEADER_SHORT_NAME, HEADER_PRICE_SOURCE, HEADER_PRICE);

  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;

  public FactorAdjustExcelParseServiceImpl(
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper factorMonthlyPriceMapper) {
    this.factorIdentityMapper = factorIdentityMapper;
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
  }

  @Override
  public FactorAdjustExcelParseResult parse(
      InputStream input, String sourceFileName, String pricingMonth, String businessUnitType) {
    FactorAdjustExcelParseResult result = new FactorAdjustExcelParseResult();
    result.setSourceFileName(sourceFileName);
    result.setPricingMonth(normalize(pricingMonth));
    result.setBusinessUnitType(normalize(businessUnitType));
    if (input == null) {
      return result;
    }
    try (Workbook workbook = WorkbookFactory.create(input)) {
      DataFormatter formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        parseSheet(workbook.getSheetAt(i), formatter, evaluator, result);
      }
    } catch (Exception e) {
      FactorAdjustExcelParseRow error = new FactorAdjustExcelParseRow();
      error.setStatus(STATUS_FAILED);
      error.setFailReason("Excel 调价解析失败: " + e.getMessage());
      result.addRow(error);
    }
    return result;
  }

  private void parseSheet(
      Sheet sheet,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      FactorAdjustExcelParseResult result) {
    if (sheet == null) {
      return;
    }
    HeaderMatch header = findHeader(sheet, formatter, evaluator);
    if (header == null) {
      return;
    }
    if (!header.columns().keySet().containsAll(REQUIRED_HEADERS)) {
      FactorAdjustExcelParseRow error = new FactorAdjustExcelParseRow();
      error.setSourceSheetName(sheet.getSheetName());
      error.setSourceRowNumber(header.rowNumber());
      error.setStatus(STATUS_FAILED);
      error.setFailReason("影响因素表头不完整，必须包含：序号、价表影响因素名称、简称、取价来源、价格");
      result.addRow(error);
      return;
    }
    for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row excelRow = sheet.getRow(rowIndex);
      if (excelRow == null || isBlankRow(excelRow, formatter, evaluator)) {
        continue;
      }
      FactorAdjustExcelParseRow row =
          readRow(sheet.getSheetName(), excelRow, header.columns(), formatter, evaluator);
      resolveIdentity(row, result.getPricingMonth(), result.getBusinessUnitType());
      result.addRow(row);
    }
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
    int last = Math.min(sheet.getLastRowNum(), 20);
    for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = readHeaderColumns(row, formatter, evaluator);
      if (columns.keySet().containsAll(REQUIRED_HEADERS)) {
        return new HeaderMatch(rowIndex, rowIndex + 1, columns);
      }
    }
    return null;
  }

  private Map<String, Integer> readHeaderColumns(
      Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    Map<String, Integer> columns = new HashMap<>();
    short last = row.getLastCellNum();
    for (int col = 0; col < last; col++) {
      String header = canonicalHeader(cellText(row.getCell(col), formatter, evaluator));
      if (StringUtils.hasText(header)) {
        columns.put(header, col);
      }
    }
    return columns;
  }

  private FactorAdjustExcelParseRow readRow(
      String sheetName,
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      FormulaEvaluator evaluator) {
    FactorAdjustExcelParseRow result = new FactorAdjustExcelParseRow();
    result.setSourceSheetName(sheetName);
    result.setSourceRowNumber(row.getRowNum() + 1);
    result.setFactorSeqNo(text(row, columns.get(HEADER_SEQ), formatter, evaluator));
    result.setFactorName(text(row, columns.get(HEADER_FACTOR_NAME), formatter, evaluator));
    result.setShortName(text(row, columns.get(HEADER_SHORT_NAME), formatter, evaluator));
    result.setPriceSource(text(row, columns.get(HEADER_PRICE_SOURCE), formatter, evaluator));
    result.setPrice(decimal(row, columns.get(HEADER_PRICE), formatter, evaluator));
    result.setOriginalPrice(decimal(row, originalPriceColumn(columns), formatter, evaluator));
    result.setUnit(text(row, columns.get(HEADER_UNIT), formatter, evaluator));
    result.setFactorIdentityId(id(row, factorIdentityColumn(columns), formatter, evaluator));
    result.setFactorMonthlyPriceId(id(row, monthlyPriceColumn(columns), formatter, evaluator));
    return result;
  }

  private void resolveIdentity(
      FactorAdjustExcelParseRow row, String pricingMonth, String businessUnitType) {
    String validateError = validateRow(row, businessUnitType);
    if (validateError != null) {
      markFailed(row, validateError);
      return;
    }
    if (row.getFactorIdentityId() != null) {
      resolveBySystemId(row, pricingMonth, businessUnitType);
      return;
    }
    resolveByIdentityFields(row, pricingMonth, businessUnitType);
  }

  private void resolveBySystemId(
      FactorAdjustExcelParseRow row, String pricingMonth, String businessUnitType) {
    FactorIdentity identity = factorIdentityMapper.selectById(row.getFactorIdentityId());
    if (identity == null) {
      markFailed(row, "影响因素ID不存在：" + row.getFactorIdentityId());
      return;
    }
    if (!normalize(identity.getBusinessUnitType()).equals(normalize(businessUnitType))) {
      markFailed(row, "影响因素ID所属业务单元不一致");
      return;
    }
    row.setMatchMethod(FactorAdjustMatchMethod.SYSTEM_ID.getCode());
    attachMonthlyPrice(row, pricingMonth, identity.getId());
    row.setStatus(STATUS_MATCHED);
  }

  private void resolveByIdentityFields(
      FactorAdjustExcelParseRow row, String pricingMonth, String businessUnitType) {
    List<FactorIdentity> identities = factorIdentityMapper.selectList(
        Wrappers.lambdaQuery(FactorIdentity.class)
            .eq(FactorIdentity::getBusinessUnitType, normalize(businessUnitType))
            .eq(FactorIdentity::getFactorSeqNo, normalize(row.getFactorSeqNo()))
            .eq(FactorIdentity::getFactorName, normalize(row.getFactorName()))
            .eq(FactorIdentity::getShortName, normalize(row.getShortName()))
            .eq(FactorIdentity::getPriceSource, normalize(row.getPriceSource()))
            .eq(FactorIdentity::getStatus, "ACTIVE"));
    if (identities == null || identities.isEmpty()) {
      markFailed(row, "未匹配到已有影响因素身份");
      return;
    }
    if (identities.size() > 1) {
      markFailed(row, "按身份字段匹配到多条影响因素，请先清理重复身份");
      return;
    }
    FactorIdentity identity = identities.getFirst();
    row.setFactorIdentityId(identity.getId());
    row.setMatchMethod(FactorAdjustMatchMethod.IDENTITY_FIELDS.getCode());
    attachMonthlyPrice(row, pricingMonth, identity.getId());
    row.setStatus(STATUS_MATCHED);
  }

  private void attachMonthlyPrice(
      FactorAdjustExcelParseRow row, String pricingMonth, Long factorIdentityId) {
    if (row.getFactorMonthlyPriceId() != null) {
      FactorMonthlyPrice monthlyPrice =
          factorMonthlyPriceMapper.selectById(row.getFactorMonthlyPriceId());
      if (monthlyPrice != null
          && factorIdentityId.equals(monthlyPrice.getFactorIdentityId())
          && normalize(pricingMonth).equals(normalize(monthlyPrice.getPriceMonth()))) {
        return;
      }
      // 模板里的月度价格 ID 可能来自上月导出；调价导入按目标月份重新查询，不直接失败。
      row.setFactorMonthlyPriceId(null);
    }
    FactorMonthlyPrice monthlyPrice = factorMonthlyPriceMapper.selectOne(
        Wrappers.lambdaQuery(FactorMonthlyPrice.class)
            .eq(FactorMonthlyPrice::getFactorIdentityId, factorIdentityId)
            .eq(FactorMonthlyPrice::getPriceMonth, normalize(pricingMonth))
            .last("LIMIT 1"));
    if (monthlyPrice != null) {
      row.setFactorMonthlyPriceId(monthlyPrice.getId());
    }
  }

  private String validateRow(FactorAdjustExcelParseRow row, String businessUnitType) {
    if (!StringUtils.hasText(businessUnitType)) {
      return "businessUnitType 必填";
    }
    if (!StringUtils.hasText(row.getFactorSeqNo())) {
      return "序号不能为空";
    }
    if (!StringUtils.hasText(row.getFactorName())) {
      return "价表影响因素名称不能为空";
    }
    if (!StringUtils.hasText(row.getShortName())) {
      return "简称不能为空";
    }
    if (!StringUtils.hasText(row.getPriceSource())) {
      return "取价来源不能为空";
    }
    if (row.getPrice() == null) {
      return "价格不能为空或格式非法";
    }
    return null;
  }

  private void markFailed(FactorAdjustExcelParseRow row, String reason) {
    row.setStatus(STATUS_FAILED);
    row.setFailReason(reason);
  }

  private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    for (int col = 0; col < row.getLastCellNum(); col++) {
      if (StringUtils.hasText(cellText(row.getCell(col), formatter, evaluator))) {
        return false;
      }
    }
    return true;
  }

  private Integer originalPriceColumn(Map<String, Integer> columns) {
    Integer col = columns.get(HEADER_ORIGINAL_PRICE);
    return col == null ? columns.get(HEADER_ORIGINAL_PRICE_LEGACY) : col;
  }

  private Integer factorIdentityColumn(Map<String, Integer> columns) {
    Integer col = columns.get(HEADER_FACTOR_ID);
    return col == null ? columns.get("factor_identity_id") : col;
  }

  private Integer monthlyPriceColumn(Map<String, Integer> columns) {
    Integer col = columns.get(HEADER_FACTOR_MONTHLY_PRICE_ID);
    return col == null ? columns.get("factor_monthly_price_id") : col;
  }

  private String text(Row row, Integer col, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (row == null || col == null) {
      return null;
    }
    String text = cellText(row.getCell(col), formatter, evaluator);
    return StringUtils.hasText(text) ? text.trim() : null;
  }

  private BigDecimal decimal(
      Row row, Integer col, DataFormatter formatter, FormulaEvaluator evaluator) {
    String text = text(row, col, formatter, evaluator);
    if (!StringUtils.hasText(text)) {
      return null;
    }
    String normalized = text.replace(",", "").trim();
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Long id(Row row, Integer col, DataFormatter formatter, FormulaEvaluator evaluator) {
    BigDecimal value = decimal(row, col, formatter, evaluator);
    return value == null ? null : value.longValue();
  }

  private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (cell == null) {
      return "";
    }
    if (cell.getCellType() == CellType.FORMULA) {
      return formatter.formatCellValue(cell, evaluator);
    }
    return formatter.formatCellValue(cell);
  }

  private String canonicalHeader(String text) {
    if (text == null) {
      return "";
    }
    return text
        .replace("\u00A0", "")
        .replace(" ", "")
        .replace("\t", "")
        .trim();
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private record HeaderMatch(int rowIndex, int rowNumber, Map<String, Integer> columns) {
  }
}
