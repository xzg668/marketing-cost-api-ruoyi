package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.LinkedFormulaRow;
import com.sanhua.marketingcost.dto.LinkedFormulaSheetParseResult;
import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import com.sanhua.marketingcost.service.PriceLinkedFormulaWorkbookParser;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public class PriceLinkedFormulaWorkbookParserImpl implements PriceLinkedFormulaWorkbookParser {

  private static final String HEADER_MATERIAL_CODE = "物料代码";
  private static final String HEADER_SUPPLIER_CODE = "供应商代码";
  private static final String HEADER_SPEC_MODEL = "规格型号";
  private static final String HEADER_FORMULA = "联动公式";
  private static final String HEADER_UNIT_PRICE = "单价";
  private static final String HEADER_ORDER_TYPE = "订单类型";
  private static final String ORDER_TYPE_FIXED = "固定";
  private static final Pattern LOCAL_CELL_REF_PATTERN =
      Pattern.compile("(?<![!$A-Za-z0-9_\\]'])\\$?([A-Za-z]{1,3})\\$?(\\d+)");

  @Override
  public LinkedFormulaWorkbookParseResult parse(InputStream input, String sourceFileName) {
    LinkedFormulaWorkbookParseResult result = new LinkedFormulaWorkbookParseResult();
    result.setSourceFileName(sourceFileName);
    if (input == null) {
      result.getErrors().add("Excel 流为空");
      return result;
    }
    try (Workbook workbook = WorkbookFactory.create(input)) {
      DataFormatter formatter = new DataFormatter();
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        LinkedFormulaSheetParseResult sheet = parseSheet(
            workbook.getSheetAt(i), formatter, evaluator);
        if (sheet != null) {
          result.getSheets().add(sheet);
        }
      }
    } catch (Exception e) {
      result.getErrors().add("Excel 联动公式解析失败: " + e.getMessage());
    }
    return result;
  }

  private LinkedFormulaSheetParseResult parseSheet(
      Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (sheet == null) {
      return null;
    }
    HeaderMatch header = findHeader(sheet, formatter, evaluator);
    if (header == null) {
      return null;
    }
    LinkedFormulaSheetParseResult result = new LinkedFormulaSheetParseResult();
    result.setSheetName(sheet.getSheetName());
    result.setHeaderRowNumber(header.rowNumber);
    for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || isBlankRow(row, formatter, evaluator)) {
        continue;
      }
      LinkedFormulaRow parsedRow = parseRow(sheet.getSheetName(), row, header.columns,
          formatter, evaluator);
      if (parsedRow == null) {
        continue;
      }
      result.getRows().add(parsedRow);
    }
    return result;
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
    int last = Math.min(sheet.getLastRowNum(), 20);
    for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = readHeaderColumns(row, formatter, evaluator);
      if (columns.containsKey(HEADER_MATERIAL_CODE) && columns.containsKey(HEADER_UNIT_PRICE)) {
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
      String text = canonicalHeader(cellText(row.getCell(col), formatter, evaluator));
      if (StringUtils.hasText(text)) {
        columns.put(text, col);
      }
    }
    return columns;
  }

  private LinkedFormulaRow parseRow(
      String sheetName,
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      FormulaEvaluator evaluator) {
    String materialCode = text(row, columns.get(HEADER_MATERIAL_CODE), formatter, evaluator);
    if (!StringUtils.hasText(materialCode)) {
      return null;
    }
    String orderType = text(row, columns.get(HEADER_ORDER_TYPE), formatter, evaluator);
    if (ORDER_TYPE_FIXED.equals(orderType)) {
      return null;
    }
    String supplierCode = text(row, columns.get(HEADER_SUPPLIER_CODE), formatter, evaluator);
    String specModel = text(row, columns.get(HEADER_SPEC_MODEL), formatter, evaluator);
    Cell priceCell = row.getCell(columns.get(HEADER_UNIT_PRICE));
    String priceFormula = priceCell != null && priceCell.getCellType() == CellType.FORMULA
        ? priceCell.getCellFormula()
        : null;

    LinkedFormulaRow result = new LinkedFormulaRow();
    result.setSourceSheetName(sheetName);
    result.setExcelRowNumber(row.getRowNum() + 1);
    result.setMaterialCode(materialCode);
    result.setLinkedItemImportKey(importKey(materialCode, supplierCode, specModel));
    result.setFormulaText(text(row, columns.get(HEADER_FORMULA), formatter, evaluator));
    result.setPriceCellFormula(priceFormula);
    result.setPriceCellValue(priceCellValue(priceCell, formatter, evaluator));
    result.setExcelDerivedFormulaText(deriveFormulaText(priceFormula, columns));
    result.setHasFormula(StringUtils.hasText(priceFormula));
    return result;
  }

  private BigDecimal priceCellValue(
      Cell priceCell, DataFormatter formatter, FormulaEvaluator evaluator) {
    if (priceCell == null) {
      return null;
    }
    if (priceCell.getCellType() == CellType.NUMERIC) {
      return BigDecimal.valueOf(priceCell.getNumericCellValue());
    }
    String text;
    try {
      text = formatter.formatCellValue(priceCell, evaluator);
    } catch (RuntimeException ex) {
      text = formatter.formatCellValue(priceCell);
    }
    return parseDecimal(text);
  }

  private BigDecimal parseDecimal(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String normalized = raw.trim().replace(",", "");
    if (!StringUtils.hasText(normalized) || "-".equals(normalized)) {
      return null;
    }
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String deriveFormulaText(String formula, Map<String, Integer> columns) {
    if (!StringUtils.hasText(formula)) {
      return null;
    }
    String derived = formula.trim();
    if (derived.startsWith("=")) {
      derived = derived.substring(1);
    }
    derived = stripRoundFunctions(derived);
    derived = replaceLocalCellRefs(derived, columns);
    return StringUtils.hasText(derived) ? derived : null;
  }

  private String stripRoundFunctions(String formula) {
    String result = formula;
    while (true) {
      int roundStart = indexOfIgnoreCase(result, "ROUND(");
      if (roundStart < 0) {
        return result;
      }
      int open = roundStart + "ROUND".length();
      int close = findMatchingParen(result, open);
      if (close < 0) {
        return result;
      }
      int comma = findTopLevelComma(result, open + 1, close);
      if (comma < 0) {
        return result;
      }
      String inner = result.substring(open + 1, comma);
      result = result.substring(0, roundStart) + "(" + inner + ")" + result.substring(close + 1);
    }
  }

  private int indexOfIgnoreCase(String text, String needle) {
    return text == null ? -1
        : text.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));
  }

  private int findMatchingParen(String text, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private int findTopLevelComma(String text, int startInclusive, int endExclusive) {
    int depth = 0;
    for (int i = startInclusive; i < endExclusive; i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private String replaceLocalCellRefs(String formula, Map<String, Integer> columns) {
    Matcher matcher = LOCAL_CELL_REF_PATTERN.matcher(formula);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String token = tokenForColumn(matcher.group(1), columns);
      if (token == null) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
      } else {
        matcher.appendReplacement(out, Matcher.quoteReplacement(token));
      }
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private String tokenForColumn(String columnLetter, Map<String, Integer> columns) {
    if (!StringUtils.hasText(columnLetter) || columns == null || columns.isEmpty()) {
      return null;
    }
    int columnIndex = columnIndex(columnLetter);
    String header = headerByColumn(columns, columnIndex);
    if (!StringUtils.hasText(header)) {
      return null;
    }
    return switch (header) {
      case "下料重", "下料重量" -> "[blank_weight]";
      case "净重", "产品净重" -> "[net_weight]";
      case "加工费", "含税加工费" -> "[process_fee]";
      case "代理费" -> "[agent_fee]";
      default -> null;
    };
  }

  private String headerByColumn(Map<String, Integer> columns, int columnIndex) {
    for (Map.Entry<String, Integer> entry : columns.entrySet()) {
      if (entry.getValue() != null && entry.getValue() == columnIndex) {
        return entry.getKey();
      }
    }
    return null;
  }

  private int columnIndex(String columnLetter) {
    int value = 0;
    String upper = columnLetter.toUpperCase(java.util.Locale.ROOT);
    for (int i = 0; i < upper.length(); i++) {
      value = value * 26 + (upper.charAt(i) - 'A' + 1);
    }
    return value - 1;
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

  private String importKey(String materialCode, String supplierCode, String specModel) {
    return trimToEmpty(materialCode) + "|" + trimToEmpty(supplierCode) + "|"
        + trimToEmpty(specModel);
  }

  private String trimToEmpty(String text) {
    return StringUtils.hasText(text) ? text.trim() : "";
  }

  private record HeaderMatch(int rowIndex, int rowNumber, Map<String, Integer> columns) {
  }
}
