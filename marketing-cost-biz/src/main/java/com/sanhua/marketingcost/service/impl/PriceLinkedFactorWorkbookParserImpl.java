package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.service.PriceLinkedFactorWorkbookParser;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedFactorWorkbookParserImpl implements PriceLinkedFactorWorkbookParser {

  private static final String HEADER_SEQ = "序号";
  private static final String HEADER_FACTOR_NAME = "价表影响因素名称";
  private static final String HEADER_SHORT_NAME = "简称";
  private static final String HEADER_PRICE_SOURCE = "取价来源";
  private static final String HEADER_PRICE = "价格";
  private static final String HEADER_UNIT = "单位";
  private static final String HEADER_ORIGINAL_PRICE = "价格-原价";

  private static final Set<String> REQUIRED_HEADERS =
      Set.of(HEADER_SEQ, HEADER_FACTOR_NAME, HEADER_SHORT_NAME, HEADER_PRICE_SOURCE, HEADER_PRICE);
  private static final Set<String> CANDIDATE_HEADERS =
      Set.of(HEADER_FACTOR_NAME, HEADER_SHORT_NAME, HEADER_PRICE_SOURCE, HEADER_PRICE);

  @Override
  public FactorWorkbookParseResult parse(InputStream input, String sourceFileName) {
    FactorWorkbookParseResult result = new FactorWorkbookParseResult();
    result.setSourceFileName(sourceFileName);
    if (input == null) {
      return result;
    }
    try (Workbook workbook = WorkbookFactory.create(input)) {
      DataFormatter formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        Sheet sheet = workbook.getSheetAt(i);
        FactorSheetParseResult parsedSheet = parseSheet(sheet, formatter, evaluator);
        if (parsedSheet != null) {
          result.getSheets().add(parsedSheet);
        }
      }
    } catch (Exception e) {
      FactorSheetParseResult errorSheet = new FactorSheetParseResult();
      errorSheet.setSheetName(null);
      errorSheet.getErrors().add(new FactorSheetParseResult.ParseError(null,
          "Excel 影响因素解析失败: " + e.getMessage()));
      result.getSheets().add(errorSheet);
    }
    return result;
  }

  private FactorSheetParseResult parseSheet(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (sheet == null) {
      return null;
    }
    HeaderMatch header = findHeader(sheet, formatter, evaluator);
    if (header == null) {
      return null;
    }
    FactorSheetParseResult result = new FactorSheetParseResult();
    result.setSheetName(sheet.getSheetName());
    result.setHeaderRowNumber(header.rowNumber);
    if (!header.columns.keySet().containsAll(REQUIRED_HEADERS)) {
      result.getErrors().add(new FactorSheetParseResult.ParseError(header.rowNumber,
          "影响因素表头不完整，必须包含：序号、价表影响因素名称、简称、取价来源、价格"));
      return result;
    }
    for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || isBlankRow(row, formatter, evaluator)) {
        continue;
      }
      FactorRowParseResult parsedRow = parseRow(
          sheet.getSheetName(), row, header.columns, formatter, evaluator);
      if (!StringUtils.hasText(parsedRow.getFactorSeqNo())) {
        result.getErrors().add(new FactorSheetParseResult.ParseError(rowIndex + 1,
            "影响因素行缺少序号，不能进入自动绑定"));
        continue;
      }
      result.getRows().add(parsedRow);
    }
    return result;
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
    int last = Math.min(sheet.getLastRowNum(), 20);
    HeaderMatch bestCandidate = null;
    for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = readHeaderColumns(row, formatter, evaluator);
      if (columns.keySet().containsAll(REQUIRED_HEADERS)) {
        return new HeaderMatch(rowIndex, rowIndex + 1, columns);
      }
      if (bestCandidate == null && columns.keySet().containsAll(CANDIDATE_HEADERS)) {
        bestCandidate = new HeaderMatch(rowIndex, rowIndex + 1, columns);
      }
    }
    return bestCandidate;
  }

  private Map<String, Integer> readHeaderColumns(
      Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    Map<String, Integer> columns = new HashMap<>();
    short last = row.getLastCellNum();
    for (int col = 0; col < last; col++) {
      String text = canonicalHeader(cellText(row.getCell(col), formatter, evaluator));
      if (REQUIRED_HEADERS.contains(text) || HEADER_UNIT.equals(text)
          || HEADER_ORIGINAL_PRICE.equals(text)) {
        columns.put(text, col);
      }
    }
    return columns;
  }

  private FactorRowParseResult parseRow(
      String sheetName,
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      FormulaEvaluator evaluator) {
    FactorRowParseResult result = new FactorRowParseResult();
    result.setSourceSheetName(sheetName);
    result.setSourceRowNumber(row.getRowNum() + 1);
    result.setFactorSeqNo(text(row, columns.get(HEADER_SEQ), formatter, evaluator));
    result.setFactorName(text(row, columns.get(HEADER_FACTOR_NAME), formatter, evaluator));
    result.setShortName(text(row, columns.get(HEADER_SHORT_NAME), formatter, evaluator));
    result.setPriceSource(text(row, columns.get(HEADER_PRICE_SOURCE), formatter, evaluator));
    result.setPrice(decimal(row, columns.get(HEADER_PRICE), formatter, evaluator));
    result.setUnit(text(row, columns.get(HEADER_UNIT), formatter, evaluator));
    result.setOriginalPrice(decimal(row, columns.get(HEADER_ORIGINAL_PRICE), formatter, evaluator));
    return result;
  }

  private boolean isBlankRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    for (int col = 0; col < row.getLastCellNum(); col++) {
      if (StringUtils.hasText(cellText(row.getCell(col), formatter, evaluator))) {
        return false;
      }
    }
    return true;
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

  private record HeaderMatch(int rowIndex, int rowNumber, Map<String, Integer> columns) {
  }
}
