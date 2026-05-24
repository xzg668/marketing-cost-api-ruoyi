package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import com.sanhua.marketingcost.service.SupplierSupplyRatioWorkbookParser;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SupplierSupplyRatioWorkbookParserImpl implements SupplierSupplyRatioWorkbookParser {
  private static final String TARGET_SHEET_NAME = "供货比例-SRM";
  private static final String HEADER_MATERIAL_CODE = "物料代码";
  private static final String HEADER_MATERIAL_NAME = "物料名称";
  private static final String HEADER_SPEC_MODEL = "型号";
  private static final String HEADER_UNIT = "单位";
  private static final String HEADER_MATERIAL_SHAPE = "物料形态属性";
  private static final String HEADER_SUPPLIER_NAME = "供应商";
  private static final String HEADER_SUPPLY_RATIO = "供货比例";
  private static final String HEADER_RULE = "规则：取供货比例大的";

  private static final List<String> ORDERED_HEADERS =
      List.of(
          HEADER_MATERIAL_CODE,
          HEADER_MATERIAL_NAME,
          HEADER_SPEC_MODEL,
          HEADER_UNIT,
          HEADER_MATERIAL_SHAPE,
          HEADER_SUPPLIER_NAME,
          HEADER_SUPPLY_RATIO,
          HEADER_RULE);
  private static final Set<String> REQUIRED_HEADERS =
      Set.of(
          HEADER_MATERIAL_CODE,
          HEADER_MATERIAL_NAME,
          HEADER_SPEC_MODEL,
          HEADER_UNIT,
          HEADER_MATERIAL_SHAPE,
          HEADER_SUPPLIER_NAME,
          HEADER_SUPPLY_RATIO);

  @Override
  public SupplierSupplyRatioWorkbookParseResult parse(InputStream input, String sourceFileName) {
    SupplierSupplyRatioWorkbookParseResult result = new SupplierSupplyRatioWorkbookParseResult();
    result.setSourceFileName(sourceFileName);
    if (input == null) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(null, null, "Excel 流为空"));
      return result;
    }
    try (Workbook workbook = WorkbookFactory.create(input)) {
      Sheet sheet = workbook.getSheet(TARGET_SHEET_NAME);
      if (sheet == null) {
        result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(null, null,
            "未找到 sheet：" + TARGET_SHEET_NAME));
        return result;
      }
      DataFormatter formatter = new DataFormatter(Locale.CHINA);
      parseSheet(sheet, formatter, result);
    } catch (Exception e) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(null, null,
          "Excel 供货比例解析失败: " + e.getMessage()));
    }
    return result;
  }

  private void parseSheet(
      Sheet sheet,
      DataFormatter formatter,
      SupplierSupplyRatioWorkbookParseResult result) {
    result.setSheetName(sheet.getSheetName());
    HeaderMatch header = findHeader(sheet, formatter);
    if (header == null) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(null, null,
          "未找到供货比例表头"));
      return;
    }
    result.setHeaderRowNumber(header.rowNumber);
    result.getHeaders().addAll(ORDERED_HEADERS);
    if (!header.columns.keySet().containsAll(REQUIRED_HEADERS)) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(header.rowNumber, null,
          "供货比例表头不完整，必须包含：物料代码、物料名称、型号、单位、物料形态属性、供应商、供货比例"));
      return;
    }
    for (int rowIndex = header.rowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || isBlankRow(row, header.columns, formatter)) {
        continue;
      }
      SupplierSupplyRatioExcelRow parsed = parseRow(row, header.columns, formatter, result);
      if (parsed != null) {
        result.getRows().add(parsed);
      }
    }
  }

  private HeaderMatch findHeader(Sheet sheet, DataFormatter formatter) {
    int last = Math.min(sheet.getLastRowNum(), 20);
    for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = readHeaderColumns(row, formatter);
      if (columns.keySet().containsAll(REQUIRED_HEADERS)) {
        return new HeaderMatch(rowIndex, rowIndex + 1, columns);
      }
    }
    return null;
  }

  private Map<String, Integer> readHeaderColumns(
      Row row, DataFormatter formatter) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    short last = row.getLastCellNum();
    for (int col = 0; col < last; col++) {
      String text = canonicalHeader(cellText(row.getCell(col), formatter));
      if (ORDERED_HEADERS.contains(text)) {
        columns.put(text, col);
      }
    }
    return columns;
  }

  private SupplierSupplyRatioExcelRow parseRow(
      Row row,
      Map<String, Integer> columns,
      DataFormatter formatter,
      SupplierSupplyRatioWorkbookParseResult result) {
    int rowNo = row.getRowNum() + 1;
    String materialCode = text(row, columns.get(HEADER_MATERIAL_CODE), formatter);
    String materialName = text(row, columns.get(HEADER_MATERIAL_NAME), formatter);
    String specModel = text(row, columns.get(HEADER_SPEC_MODEL), formatter);
    String supplierName = text(row, columns.get(HEADER_SUPPLIER_NAME), formatter);
    BigDecimal supplyRatio = decimal(row, columns.get(HEADER_SUPPLY_RATIO), formatter, result, rowNo);
    if (!StringUtils.hasText(SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(materialCode))) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(rowNo, HEADER_MATERIAL_CODE,
          "物料代码不能为空"));
      return null;
    }
    if (!StringUtils.hasText(SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(supplierName))) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(rowNo, HEADER_SUPPLIER_NAME,
          "供应商不能为空"));
      return null;
    }
    if (supplyRatio == null) {
      return null;
    }
    SupplierSupplyRatioExcelRow parsed = new SupplierSupplyRatioExcelRow();
    parsed.setRowNo(rowNo);
    parsed.setMaterialCode(materialCode);
    parsed.setMaterialName(materialName);
    parsed.setSpecModel(specModel);
    parsed.setUnit(text(row, columns.get(HEADER_UNIT), formatter));
    parsed.setMaterialShape(text(row, columns.get(HEADER_MATERIAL_SHAPE), formatter));
    parsed.setSupplierName(supplierName);
    parsed.setSupplyRatio(supplyRatio);
    // SSR-01 基线：导入幂等键固定为 物料代码 + 物料名称 + 供应商 + 型号。
    parsed.setDedupeKey(
        SupplierSupplyRatioNormalizeUtils.buildDedupeKey(
            materialCode, materialName, supplierName, specModel));
    return parsed;
  }

  private boolean isBlankRow(Row row, Map<String, Integer> columns, DataFormatter formatter) {
    for (Integer col : columns.values()) {
      if (StringUtils.hasText(cellText(row.getCell(col), formatter))) {
        return false;
      }
    }
    return true;
  }

  private String text(Row row, Integer col, DataFormatter formatter) {
    if (row == null || col == null) {
      return null;
    }
    String text = cellText(row.getCell(col), formatter);
    return StringUtils.hasText(text) ? text.trim() : null;
  }

  private BigDecimal decimal(
      Row row,
      Integer col,
      DataFormatter formatter,
      SupplierSupplyRatioWorkbookParseResult result,
      int rowNo) {
    String text = text(row, col, formatter);
    if (!StringUtils.hasText(text)) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(rowNo, HEADER_SUPPLY_RATIO,
          "供货比例不能为空"));
      return null;
    }
    String normalized = text.replace(",", "").trim();
    boolean percent = normalized.endsWith("%");
    if (percent) {
      normalized = normalized.substring(0, normalized.length() - 1).trim();
    }
    try {
      BigDecimal value = new BigDecimal(normalized);
      return percent ? value.divide(new BigDecimal("100")) : value;
    } catch (NumberFormatException e) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(rowNo, HEADER_SUPPLY_RATIO,
          "供货比例数字格式不正确: " + text));
      return null;
    }
  }

  private String cellText(Cell cell, DataFormatter formatter) {
    if (cell == null) {
      return "";
    }
    if (cell.getCellType() == CellType.FORMULA) {
      // 真实样例部分字段是跨工作簿 VLOOKUP。这里读取 Excel 保存的公式缓存值，避免 POI 解析外部工作簿失败或把公式文本写进去重键。
      return cachedFormulaText(cell, formatter);
    }
    return formatter.formatCellValue(cell);
  }

  private String cachedFormulaText(Cell cell, DataFormatter formatter) {
    return switch (cell.getCachedFormulaResultType()) {
      case STRING -> cell.getStringCellValue();
      case NUMERIC -> formatter.formatRawCellContents(
          cell.getNumericCellValue(),
          cell.getCellStyle().getDataFormat(),
          cell.getCellStyle().getDataFormatString());
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case ERROR, BLANK, _NONE -> "";
      case FORMULA -> formatter.formatCellValue(cell);
    };
  }

  private String canonicalHeader(String text) {
    return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(text);
  }

  private record HeaderMatch(int rowIndex, int rowNumber, Map<String, Integer> columns) {
  }
}
