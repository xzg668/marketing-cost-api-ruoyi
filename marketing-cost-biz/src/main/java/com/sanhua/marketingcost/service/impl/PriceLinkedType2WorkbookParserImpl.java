package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FactorRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2ParseError;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import com.sanhua.marketingcost.service.PriceLinkedType2WorkbookParser;
import com.sanhua.marketingcost.service.PriceLinkedWorkbookTypeDetector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PriceLinkedType2WorkbookParserImpl implements PriceLinkedType2WorkbookParser {

  private static final String HEADER_SEQUENCE = "序号";
  private static final String HEADER_MATERIAL_CODE = "U9代码";
  private static final String HEADER_PRODUCT_NAME = "产品名称";
  private static final String HEADER_SPECIFICATION = "型号规格";
  private static final String HEADER_UNIT = "单位";
  private static final String HEADER_SUPPLIER_NAME = "供应商名称";
  private static final String HEADER_TAX_INCLUDED_PRICE = "现含税价";
  private static final String HEADER_TAX_EXCLUDED_PRICE = "现不含税价";

  private static final String STANDARD_MATERIAL_CODE = "物料代码";
  private static final String STANDARD_SUPPLIER_CODE = "供应商代码";
  private static final Set<String> BUSINESS_REQUIRED_HEADERS =
      normalizedHeaders(HEADER_MATERIAL_CODE, HEADER_SUPPLIER_NAME, HEADER_TAX_INCLUDED_PRICE);
  private static final Set<String> STANDARD_REQUIRED_HEADERS =
      normalizedHeaders(
          STANDARD_MATERIAL_CODE,
          HEADER_SUPPLIER_NAME,
          STANDARD_SUPPLIER_CODE,
          "单价",
          "是否含税");

  private static final Pattern FACTOR_SHORT_NAME =
      Pattern.compile("(?i)^(?:\\d+\\s*#\\s*)?[a-z][a-z0-9._#-]*$");
  private static final Pattern CELL_REFERENCE = Pattern.compile(
      "(?<![A-Za-z0-9_])"
          + "(?:(?:'((?:[^']|'')+)'|([\\p{L}\\p{N}_ .]+))!)?"
          + "(\\$?[A-Za-z]{1,3}\\$?\\d+)"
          + "(?!\\s*\\()");

  private final PriceLinkedWorkbookTypeDetector workbookTypeDetector;

  public PriceLinkedType2WorkbookParserImpl(
      PriceLinkedWorkbookTypeDetector workbookTypeDetector) {
    this.workbookTypeDetector = workbookTypeDetector;
  }

  @Override
  public PriceLinkedType2WorkbookParseResult parse(
      InputStream input, String sourceFileName) {
    ParseAccumulator parsed = new ParseAccumulator(sourceFileName);
    if (input == null) {
      parsed.error(null, null, null, "Excel 流不能为空");
      return parsed.toResult();
    }

    byte[] workbookBytes;
    try {
      workbookBytes = input.readAllBytes();
    } catch (IOException ex) {
      parsed.error(null, null, null, "无法读取 Excel 流: " + ex.getMessage());
      return parsed.toResult();
    }

    PriceLinkedWorkbookDetectionResult detection;
    try {
      detection = workbookTypeDetector.detect(new ByteArrayInputStream(workbookBytes));
    } catch (RuntimeException ex) {
      parsed.error(null, null, null, "无法识别 Excel 类型: " + ex.getMessage());
      return parsed.toResult();
    }
    if (detection.getType() != PriceLinkedWorkbookType.TYPE2
        || detection.getType2CandidateSheets().size() != 1) {
      parsed.error(
          null,
          null,
          null,
          "工作簿不是唯一可解析的类型 2 模板: " + detection.getMessage());
      return parsed.toResult();
    }

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      String businessSheetName = detection.getType2CandidateSheets().getFirst();
      Sheet businessSheet = workbook.getSheet(businessSheetName);
      parseBusinessSheet(workbook, businessSheet, formatter, parsed);

      if (detection.getStandardCandidateSheets().size() == 1) {
        Sheet standardSheet = workbook.getSheet(
            detection.getStandardCandidateSheets().getFirst());
        parseStandardSheet(standardSheet, formatter, parsed);
      } else if (detection.getStandardCandidateSheets().isEmpty()) {
        parsed.error(null, null, null, "类型 2 工作簿中未识别到标准导入 Sheet");
      } else {
        parsed.error(
            null,
            null,
            null,
            "类型 2 工作簿中识别到多个标准导入 Sheet: "
                + detection.getStandardCandidateSheets());
      }
    } catch (IOException | RuntimeException ex) {
      parsed.error(null, null, null, "类型 2 Excel 原始解析失败: " + ex.getMessage());
    }
    return parsed.toResult();
  }

  private void parseBusinessSheet(
      Workbook workbook,
      Sheet sheet,
      DataFormatter formatter,
      ParseAccumulator parsed) {
    if (sheet == null) {
      parsed.error(null, null, null, "类型 2 业务计算 Sheet 不存在");
      return;
    }
    HeaderMatch productHeader = findHeader(sheet, formatter, BUSINESS_REQUIRED_HEADERS);
    if (productHeader == null) {
      parsed.error(sheet.getSheetName(), null, null, "未找到类型 2 产品明细表头");
      return;
    }
    parsed.businessSheetName = sheet.getSheetName();
    parsed.businessHeaderRowNumber = productHeader.rowIndex() + 1;

    parsed.factorRows.addAll(
        parseFactors(sheet, productHeader.rowIndex(), formatter, parsed));
    for (int rowIndex = productHeader.rowIndex() + 1;
        rowIndex <= sheet.getLastRowNum();
        rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || isBlankRow(row, formatter)) {
        continue;
      }
      String materialCode = identifierText(
          row.getCell(productHeader.column(HEADER_MATERIAL_CODE)), formatter);
      if (!StringUtils.hasText(materialCode)) {
        continue;
      }
      parsed.productRows.add(
          parseProductRow(
              workbook, sheet, row, productHeader, materialCode, formatter, parsed));
    }
  }

  private List<PriceLinkedType2FactorRow> parseFactors(
      Sheet sheet,
      int productHeaderRowIndex,
      DataFormatter formatter,
      ParseAccumulator parsed) {
    HeaderMatch factorHeader = findFactorHeader(sheet, productHeaderRowIndex, formatter);
    List<PriceLinkedType2FactorRow> factors = new ArrayList<>();
    if (factorHeader != null) {
      for (int rowIndex = factorHeader.rowIndex() + 1;
          rowIndex < productHeaderRowIndex;
          rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null || isBlankRow(row, formatter)) {
          continue;
        }
        PriceLinkedType2FactorRow factor =
            parseFactorByHeader(sheet, row, factorHeader, formatter, parsed);
        if (factor != null) {
          factors.add(factor);
        }
      }
      return factors;
    }

    for (int rowIndex = sheet.getFirstRowNum();
        rowIndex < productHeaderRowIndex;
        rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      PriceLinkedType2FactorRow factor =
          parseFactorByStructure(sheet, row, formatter);
      if (factor != null) {
        factors.add(factor);
      }
    }
    return factors;
  }

  private HeaderMatch findFactorHeader(
      Sheet sheet, int beforeRowIndex, DataFormatter formatter) {
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex < beforeRowIndex; rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<String, Integer> columns = new LinkedHashMap<>();
      Map<Integer, String> rawHeaders = readHeaders(row, formatter);
      for (Map.Entry<Integer, String> entry : rawHeaders.entrySet()) {
        String canonical = canonicalHeader(entry.getValue());
        if (isOneOf(canonical, "序号")) {
          columns.put(HEADER_SEQUENCE, entry.getKey());
        } else if (isOneOf(canonical, "影响因素名称", "价表影响因素名称", "长名称")) {
          columns.put("影响因素名称", entry.getKey());
        } else if (isOneOf(canonical, "简称")) {
          columns.put("简称", entry.getKey());
        } else if (isOneOf(canonical, "取价来源", "价格来源")) {
          columns.put("取价来源", entry.getKey());
        } else if (isOneOf(canonical, "价格", "平均价")) {
          columns.put("价格", entry.getKey());
        } else if (isOneOf(canonical, "单位")) {
          columns.put(HEADER_UNIT, entry.getKey());
        }
      }
      if (columns.containsKey(HEADER_SEQUENCE)
          && columns.containsKey("简称")
          && columns.containsKey("价格")) {
        return new HeaderMatch(rowIndex, columns, rawHeaders);
      }
    }
    return null;
  }

  private PriceLinkedType2FactorRow parseFactorByHeader(
      Sheet sheet,
      Row row,
      HeaderMatch header,
      DataFormatter formatter,
      ParseAccumulator parsed) {
    String sequence = text(cell(row, header.column(HEADER_SEQUENCE)), formatter);
    String shortName = text(cell(row, header.column("简称")), formatter);
    if (!StringUtils.hasText(sequence) && !StringUtils.hasText(shortName)) {
      return null;
    }
    Cell priceCell = cell(row, header.column("价格"));
    BigDecimal price = numericValue(priceCell);
    if (price == null && StringUtils.hasText(displayValue(priceCell, formatter))) {
      parsed.error(
          sheet.getSheetName(),
          row.getRowNum() + 1,
          cellRef(priceCell),
          "影响因素价格不是合法数字");
    }
    return new PriceLinkedType2FactorRow(
        sheet.getSheetName(),
        row.getRowNum() + 1,
        sequence,
        text(cell(row, header.column("影响因素名称")), formatter),
        shortName,
        text(cell(row, header.column("取价来源")), formatter),
        price,
        text(cell(row, header.column(HEADER_UNIT)), formatter),
        cellRef(priceCell));
  }

  private PriceLinkedType2FactorRow parseFactorByStructure(
      Sheet sheet, Row row, DataFormatter formatter) {
    if (row == null || row.getLastCellNum() < 0) {
      return null;
    }
    List<CellValue> populated = populatedCells(row, formatter);
    if (populated.size() < 5 || !looksLikeSequence(populated.getFirst().text())) {
      return null;
    }
    int shortIndex = -1;
    for (int index = 1; index < populated.size(); index++) {
      if (FACTOR_SHORT_NAME.matcher(populated.get(index).text()).matches()
          && populated.get(index).text().contains("#")) {
        shortIndex = index;
        break;
      }
    }
    if (shortIndex < 2) {
      return null;
    }

    int priceIndex = -1;
    BigDecimal price = null;
    for (int index = shortIndex + 1; index < populated.size(); index++) {
      price = numericValue(populated.get(index).cell());
      if (price != null) {
        priceIndex = index;
        break;
      }
    }
    if (priceIndex < 0) {
      return null;
    }

    String priceSource = priceIndex > shortIndex + 1
        ? populated.get(priceIndex - 1).text()
        : null;
    String unit = priceIndex + 1 < populated.size()
        ? populated.get(priceIndex + 1).text()
        : null;
    Cell priceCell = populated.get(priceIndex).cell();
    return new PriceLinkedType2FactorRow(
        sheet.getSheetName(),
        row.getRowNum() + 1,
        populated.getFirst().text(),
        populated.get(shortIndex - 1).text(),
        populated.get(shortIndex).text(),
        priceSource,
        price,
        unit,
        cellRef(priceCell));
  }

  private PriceLinkedType2ProductRow parseProductRow(
      Workbook workbook,
      Sheet sheet,
      Row row,
      HeaderMatch header,
      String materialCode,
      DataFormatter formatter,
      ParseAccumulator parsed) {
    Cell formulaCell = cell(row, header.column(HEADER_TAX_INCLUDED_PRICE));
    String formula = formulaCell != null && formulaCell.getCellType() == CellType.FORMULA
        ? formulaCell.getCellFormula()
        : null;
    BigDecimal taxIncludedPrice = numericValue(formulaCell);
    if (StringUtils.hasText(displayValue(formulaCell, formatter))
        && taxIncludedPrice == null) {
      parsed.error(
          sheet.getSheetName(),
          row.getRowNum() + 1,
          cellRef(formulaCell),
          formula == null
              ? "现含税价不是合法数字"
              : "现含税价公式缓存值为空或不是数字");
    }

    Cell taxExcludedCell = cell(row, header.column(HEADER_TAX_EXCLUDED_PRICE));
    BigDecimal taxExcludedPrice = numericValue(taxExcludedCell);
    if (StringUtils.hasText(displayValue(taxExcludedCell, formatter))
        && taxExcludedPrice == null) {
      parsed.error(
          sheet.getSheetName(),
          row.getRowNum() + 1,
          cellRef(taxExcludedCell),
          "现不含税价不是合法数字");
    }

    List<PriceLinkedType2CellSnapshot> referencedCells =
        referencedCells(workbook, sheet, row, header, formula, formatter, parsed);
    return new PriceLinkedType2ProductRow(
        sheet.getSheetName(),
        row.getRowNum() + 1,
        materialCode,
        text(cell(row, header.column(HEADER_PRODUCT_NAME)), formatter),
        text(cell(row, header.column(HEADER_SPECIFICATION)), formatter),
        text(cell(row, header.column(HEADER_UNIT)), formatter),
        text(cell(row, header.column(HEADER_SUPPLIER_NAME)), formatter),
        formula,
        cellRef(formulaCell),
        taxIncludedPrice,
        taxExcludedPrice,
        referencedCells);
  }

  private List<PriceLinkedType2CellSnapshot> referencedCells(
      Workbook workbook,
      Sheet businessSheet,
      Row productRow,
      HeaderMatch productHeader,
      String formula,
      DataFormatter formatter,
      ParseAccumulator parsed) {
    if (!StringUtils.hasText(formula)) {
      return List.of();
    }
    List<PriceLinkedType2CellSnapshot> snapshots = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = CELL_REFERENCE.matcher(formula);
    while (matcher.find()) {
      String explicitSheet = firstNonBlank(matcher.group(1), matcher.group(2));
      String sheetName = explicitSheet == null
          ? businessSheet.getSheetName()
          : explicitSheet.replace("''", "'");
      Sheet targetSheet = workbook.getSheet(sheetName);
      String referenceToken = matcher.group(3);
      if (targetSheet == null) {
        parsed.error(
            businessSheet.getSheetName(),
            productRow.getRowNum() + 1,
            null,
            "公式引用了不存在的 Sheet: " + sheetName);
        continue;
      }

      CellReference reference;
      try {
        reference = new CellReference(referenceToken.replace("$", ""));
      } catch (RuntimeException ex) {
        parsed.error(
            businessSheet.getSheetName(),
            productRow.getRowNum() + 1,
            null,
            "无法识别公式单元格引用: " + referenceToken);
        continue;
      }
      String canonicalRef = new CellReference(reference.getRow(), reference.getCol())
          .formatAsString();
      String uniqueKey = sheetName + "!" + canonicalRef;
      if (!seen.add(uniqueKey)) {
        continue;
      }

      Row targetRow = targetSheet.getRow(reference.getRow());
      Cell targetCell = targetRow == null ? null : targetRow.getCell(reference.getCol());
      String header = null;
      String unit = null;
      if (targetSheet == businessSheet && reference.getRow() == productRow.getRowNum()) {
        header = productHeader.rawHeaders().get((int) reference.getCol());
        unit = unitFromHeader(header);
      }
      PriceLinkedType2FactorRow factor =
          findFactorByCell(parsed.factorRows, sheetName, canonicalRef);
      if (factor != null) {
        header = factor.getShortName();
        unit = factor.getUnit();
      }
      snapshots.add(
          snapshot(targetSheet, targetCell, canonicalRef, header, unit, formatter));
    }
    return snapshots;
  }

  private void parseStandardSheet(
      Sheet sheet, DataFormatter formatter, ParseAccumulator parsed) {
    if (sheet == null) {
      parsed.error(null, null, null, "标准导入 Sheet 不存在");
      return;
    }
    HeaderMatch header = findHeader(sheet, formatter, STANDARD_REQUIRED_HEADERS);
    if (header == null) {
      parsed.error(sheet.getSheetName(), null, null, "未找到标准导入表头");
      return;
    }
    parsed.standardSheetName = sheet.getSheetName();
    parsed.standardHeaderRowNumber = header.rowIndex() + 1;
    for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || isBlankRow(row, formatter)) {
        continue;
      }
      String materialCode = identifierText(
          row.getCell(header.column(STANDARD_MATERIAL_CODE)), formatter);
      if (!StringUtils.hasText(materialCode)) {
        continue;
      }
      List<PriceLinkedType2CellSnapshot> cells = new ArrayList<>();
      for (Map.Entry<Integer, String> column : header.rawHeaders().entrySet()) {
        Cell cell = row.getCell(column.getKey());
        String ref = new CellReference(rowIndex, column.getKey()).formatAsString();
        cells.add(snapshot(sheet, cell, ref, column.getValue(), unitFromHeader(column.getValue()),
            formatter));
      }
      parsed.standardRows.add(
          new PriceLinkedType2StandardRow(
              sheet.getSheetName(),
              rowIndex + 1,
              materialCode,
              text(cell(row, header.column(HEADER_SUPPLIER_NAME)), formatter),
              identifierText(cell(row, header.column(STANDARD_SUPPLIER_CODE)), formatter),
              cells));
    }
  }

  private HeaderMatch findHeader(
      Sheet sheet, DataFormatter formatter, Set<String> requiredHeaders) {
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      Map<Integer, String> rawHeaders = readHeaders(row, formatter);
      Map<String, Integer> normalizedColumns = new LinkedHashMap<>();
      for (Map.Entry<Integer, String> entry : rawHeaders.entrySet()) {
        normalizedColumns.putIfAbsent(canonicalHeader(entry.getValue()), entry.getKey());
      }
      if (normalizedColumns.keySet().containsAll(requiredHeaders)) {
        return new HeaderMatch(rowIndex, normalizedColumns, rawHeaders);
      }
    }
    return null;
  }

  private Map<Integer, String> readHeaders(Row row, DataFormatter formatter) {
    Map<Integer, String> headers = new LinkedHashMap<>();
    if (row == null || row.getLastCellNum() < 0) {
      return headers;
    }
    for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
      String header = text(row.getCell(columnIndex), formatter);
      if (StringUtils.hasText(header)) {
        headers.put(columnIndex, header);
      }
    }
    return headers;
  }

  private List<CellValue> populatedCells(Row row, DataFormatter formatter) {
    List<CellValue> values = new ArrayList<>();
    for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
      Cell cell = row.getCell(columnIndex);
      String value = text(cell, formatter);
      if (StringUtils.hasText(value)) {
        values.add(new CellValue(cell, value));
      }
    }
    return values;
  }

  private PriceLinkedType2CellSnapshot snapshot(
      Sheet sheet,
      Cell cell,
      String reference,
      String header,
      String unit,
      DataFormatter formatter) {
    return new PriceLinkedType2CellSnapshot(
        sheet.getSheetName(),
        reference,
        header,
        displayValue(cell, formatter),
        numericValue(cell),
        cell != null && cell.getCellType() == CellType.FORMULA
            ? cell.getCellFormula()
            : null,
        unit,
        sourceCellType(cell),
        isPhysicalBlank(cell));
  }

  private String sourceCellType(Cell cell) {
    return cell == null ? "MISSING" : cell.getCellType().name();
  }

  private boolean isPhysicalBlank(Cell cell) {
    return cell == null || cell.getCellType() == CellType.BLANK;
  }

  private PriceLinkedType2FactorRow findFactorByCell(
      List<PriceLinkedType2FactorRow> factors, String sheetName, String cellRef) {
    for (PriceLinkedType2FactorRow factor : factors) {
      if (sheetName.equals(factor.getSourceSheetName())
          && cellRef.equalsIgnoreCase(factor.getPriceCellRef())) {
        return factor;
      }
    }
    return null;
  }

  private boolean isBlankRow(Row row, DataFormatter formatter) {
    if (row == null || row.getLastCellNum() < 0) {
      return true;
    }
    for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
      if (StringUtils.hasText(displayValue(row.getCell(columnIndex), formatter))) {
        return false;
      }
    }
    return true;
  }

  private String identifierText(Cell cell, DataFormatter formatter) {
    if (cell == null) {
      return null;
    }
    if (cell.getCellType() != CellType.NUMERIC) {
      return text(cell, formatter);
    }
    String formatted = formatter.formatCellValue(cell).replace(",", "").trim();
    if (formatted.matches("\\d+")) {
      return formatted;
    }
    BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
    return value.toPlainString();
  }

  private String text(Cell cell, DataFormatter formatter) {
    String value = displayValue(cell, formatter);
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String displayValue(Cell cell, DataFormatter formatter) {
    if (cell == null) {
      return "";
    }
    if (cell.getCellType() != CellType.FORMULA) {
      return formatter.formatCellValue(cell);
    }
    return switch (cell.getCachedFormulaResultType()) {
      case NUMERIC -> formatter.formatRawCellContents(
          cell.getNumericCellValue(),
          cell.getCellStyle().getDataFormat(),
          cell.getCellStyle().getDataFormatString());
      case STRING -> cell.getStringCellValue();
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
      case BLANK, _NONE, FORMULA -> "";
    };
  }

  private BigDecimal numericValue(Cell cell) {
    if (cell == null) {
      return null;
    }
    CellType valueType = cell.getCellType() == CellType.FORMULA
        ? cell.getCachedFormulaResultType()
        : cell.getCellType();
    if (valueType == CellType.NUMERIC) {
      return BigDecimal.valueOf(cell.getNumericCellValue());
    }
    if (valueType == CellType.STRING) {
      return parseDecimal(cell.getStringCellValue());
    }
    return null;
  }

  private BigDecimal parseDecimal(String raw) {
    if (!StringUtils.hasText(raw)) {
      return null;
    }
    String normalized = raw.replace(",", "").trim();
    if (!StringUtils.hasText(normalized) || "-".equals(normalized)) {
      return null;
    }
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String cellRef(Cell cell) {
    return cell == null
        ? null
        : new CellReference(cell.getRowIndex(), cell.getColumnIndex()).formatAsString();
  }

  private Cell cell(Row row, int columnIndex) {
    return row == null || columnIndex < 0 ? null : row.getCell(columnIndex);
  }

  private String unitFromHeader(String header) {
    if (!StringUtils.hasText(header)) {
      return null;
    }
    int open = Math.max(header.lastIndexOf('('), header.lastIndexOf('（'));
    int close = Math.max(header.lastIndexOf(')'), header.lastIndexOf('）'));
    if (open >= 0 && close > open) {
      return header.substring(open + 1, close).trim();
    }
    return null;
  }

  private boolean looksLikeSequence(String value) {
    return StringUtils.hasText(value) && value.matches("\\d+(?:\\.0+)?");
  }

  private String firstNonBlank(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first;
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private boolean isOneOf(String value, String... choices) {
    for (String choice : choices) {
      if (canonicalHeader(choice).equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> normalizedHeaders(String... headers) {
    Set<String> result = new LinkedHashSet<>();
    for (String header : headers) {
      result.add(canonicalHeader(header));
    }
    return Set.copyOf(result);
  }

  private static String canonicalHeader(String raw) {
    if (raw == null) {
      return "";
    }
    return Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace("\uFEFF", "")
        .strip()
        .replaceAll("\\s+", "")
        .toUpperCase(Locale.ROOT);
  }

  private record HeaderMatch(
      int rowIndex, Map<String, Integer> columns, Map<Integer, String> rawHeaders) {

    private int column(String header) {
      Integer column = columns.get(canonicalHeader(header));
      return column == null ? -1 : column;
    }
  }

  private record CellValue(Cell cell, String text) {
  }

  private static final class ParseAccumulator {

    private final String sourceFileName;
    private String businessSheetName;
    private Integer businessHeaderRowNumber;
    private String standardSheetName;
    private Integer standardHeaderRowNumber;
    private final List<PriceLinkedType2FactorRow> factorRows = new ArrayList<>();
    private final List<PriceLinkedType2ProductRow> productRows = new ArrayList<>();
    private final List<PriceLinkedType2StandardRow> standardRows = new ArrayList<>();
    private final List<PriceLinkedType2ParseError> errors = new ArrayList<>();

    private ParseAccumulator(String sourceFileName) {
      this.sourceFileName = sourceFileName;
    }

    private void error(
        String sheetName, Integer rowNumber, String cellRef, String message) {
      errors.add(new PriceLinkedType2ParseError(sheetName, rowNumber, cellRef, message));
    }

    private PriceLinkedType2WorkbookParseResult toResult() {
      return new PriceLinkedType2WorkbookParseResult(
          sourceFileName,
          businessSheetName,
          businessHeaderRowNumber,
          standardSheetName,
          standardHeaderRowNumber,
          factorRows,
          productRows,
          standardRows,
          errors);
    }
  }
}
