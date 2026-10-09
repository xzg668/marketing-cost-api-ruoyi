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
import java.util.ArrayList;
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
  private static final List<String> SHEET_KEYWORDS = List.of("供货比例", "供货比率", "供货比利");
  private static final String HEADER_MATERIAL_CODE = "物料代码";
  private static final String HEADER_MATERIAL_NAME = "物料名称";
  private static final String HEADER_SPEC_MODEL = "型号";
  private static final String HEADER_UNIT = "单位";
  private static final String HEADER_MATERIAL_SHAPE = "物料形态属性";
  private static final String HEADER_SUPPLIER_NAME = "供应商";
  private static final String HEADER_SUPPLIER_CODE = "供应商代码";
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
          HEADER_SUPPLIER_CODE,
          HEADER_SUPPLIER_NAME,
          HEADER_SUPPLY_RATIO);

  @Override
  public SupplierSupplyRatioWorkbookParseResult parse(InputStream input, String sourceFileName,
      String sheetName) {
    SupplierSupplyRatioWorkbookParseResult result = new SupplierSupplyRatioWorkbookParseResult();
    result.setSourceFileName(sourceFileName);
    if (input == null) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(null, null, "Excel 流为空"));
      return result;
    }
    try (Workbook workbook = WorkbookFactory.create(input)) {
      List<Sheet> candidates = new ArrayList<>();
      for (Sheet candidate : workbook) {
        String name = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(candidate.getSheetName());
        if (SHEET_KEYWORDS.stream().anyMatch(name::contains)) candidates.add(candidate);
      }
      if (candidates.isEmpty()) throw new IllegalArgumentException("未找到名称包含供货比例、供货比率或供货比利的工作表");
      Sheet sheet;
      if (StringUtils.hasText(sheetName)) {
        sheet = candidates.stream().filter(s -> s.getSheetName().equals(sheetName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("所选工作表不属于供货比例数据表"));
      } else {
        if (candidates.size() != 1) throw new IllegalArgumentException("存在多个供货比例工作表，请选择要导入的工作表");
        sheet = candidates.getFirst();
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
    if (header.columns.containsKey(HEADER_SUPPLIER_CODE)) {
      result.getHeaders().add(
          result.getHeaders().indexOf(HEADER_SUPPLY_RATIO),
          HEADER_SUPPLIER_CODE);
    }
    if (!header.columns.keySet().containsAll(REQUIRED_HEADERS)) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(header.rowNumber, null,
          "供货比例表头不完整，必须包含：物料代码、物料名称、供应商代码、供应商名称、比例"));
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
      if (ORDERED_HEADERS.contains(text) || HEADER_SUPPLIER_CODE.equals(text)) {
        columns.putIfAbsent(text, col);
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
    String supplierCode = text(row, columns.get(HEADER_SUPPLIER_CODE), formatter);
    int errorsBefore = result.getErrors().size();
    BigDecimal supplyRatio = decimal(row, columns.get(HEADER_SUPPLY_RATIO), formatter, result, rowNo);
    if (result.getErrors().size() > errorsBefore) return null;
    if (!StringUtils.hasText(supplierCode)) {
      result.getErrors().add(new SupplierSupplyRatioWorkbookParseResult.ParseError(rowNo, HEADER_SUPPLIER_CODE,
          "供应商代码不能为空"));
      return null;
    }
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
    SupplierSupplyRatioExcelRow parsed = new SupplierSupplyRatioExcelRow();
    parsed.setRowNo(rowNo);
    parsed.setMaterialCode(materialCode);
    parsed.setMaterialName(materialName);
    parsed.setSpecModel(specModel);
    parsed.setUnit(text(row, columns.get(HEADER_UNIT), formatter));
    parsed.setMaterialShape(text(row, columns.get(HEADER_MATERIAL_SHAPE), formatter));
    parsed.setSupplierName(supplierName);
    parsed.setSupplierCode(supplierCode);
    parsed.setSupplyRatio(supplyRatio);
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
    Cell cell = col == null ? null : row.getCell(col);
    // 百分比显示格式可能四舍五入；数值单元格必须按实际值导入。
    boolean numeric = cell != null && (cell.getCellType() == CellType.NUMERIC
        || (cell.getCellType() == CellType.FORMULA
            && cell.getCachedFormulaResultType() == CellType.NUMERIC));
    String text = numeric ? BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString()
        : text(row, col, formatter);
    if (!StringUtils.hasText(text) || "补充".equals(text.trim())) {
      // 保留供应关系，未填写与明确的 0% 是不同的业务含义。
      return null;
    }
    String normalized = text.replace(",", "").trim();
    boolean percent = normalized.endsWith("%");
    if (percent) {
      normalized = normalized.substring(0, normalized.length() - 1).trim();
    }
    try {
      BigDecimal value = new BigDecimal(normalized);
      BigDecimal ratio = percent ? value.divide(new BigDecimal("100")) : value;
      if (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
        throw new NumberFormatException("比例必须在0到100%之间");
      }
      return ratio;
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
    String normalized = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(text);
    if (normalized.endsWith("*")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return switch (normalized) {
      case "料号", "物料编码" -> HEADER_MATERIAL_CODE;
      case "品名" -> HEADER_MATERIAL_NAME;
      case "物料型号", "物料规格", "规格型号", "规格" -> HEADER_SPEC_MODEL;
      case "计量单位", "库存主单位" -> HEADER_UNIT;
      case "U9物料形态属性", "形态属性" -> HEADER_MATERIAL_SHAPE;
      case "供应商名称" -> HEADER_SUPPLIER_NAME;
      case "比例", "供货比率", "供货比利" -> HEADER_SUPPLY_RATIO;
      case "供应商编码", "供方代码" -> HEADER_SUPPLIER_CODE;
      default -> normalized;
    };
  }

  private record HeaderMatch(int rowIndex, int rowNumber, Map<String, Integer> columns) {
  }
}
