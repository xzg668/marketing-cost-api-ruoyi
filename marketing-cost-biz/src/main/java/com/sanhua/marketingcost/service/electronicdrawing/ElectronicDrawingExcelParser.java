package com.sanhua.marketingcost.service.electronicdrawing;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** 解析电子图库下载的工程明细 Excel；严格区分 BOM 行与印章、审核、页码等页脚。 */
@Component
public class ElectronicDrawingExcelParser {
  private static final int HEADER_SCAN_ROW_LIMIT = 20;
  private static final int MAX_DETAIL_NODES = 10_000;
  private static final Pattern SEQUENCE = Pattern.compile("[1-9]\\d*(?:\\.[1-9]\\d*)*");
  private static final List<String> REQUIRED_HEADERS = List.of(
      "序号", "代号", "名称", "材料", "物料重要性分类", "HSF风险分类", "数量", "重量", "备注");
  private static final Pattern WEIGHT_HEADER = Pattern.compile("(?:重量|单重)(?:\\(([^()]+)\\))?");
  private final DataFormatter formatter = new DataFormatter(Locale.CHINA);

  public ElectronicDrawingExcelParseResult parse(String fileName, InputStream input) {
    List<ElectronicDrawingExcelParseResult.Issue> issues = new ArrayList<>();
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
      issues.add(issue("FILE_TYPE_INVALID", null, null, "只支持电子图库下载的 .xlsx 文件"));
      return result(fileName, null, List.of(), issues);
    }
    if (input == null) {
      issues.add(issue("FILE_EMPTY", null, null, "上传文件为空"));
      return result(fileName, null, List.of(), issues);
    }

    try (BufferedInputStream buffered = new BufferedInputStream(input)) {
      buffered.mark(1);
      if (buffered.read() < 0) {
        issues.add(issue("FILE_EMPTY", null, null, "上传文件为空"));
        return result(fileName, null, List.of(), issues);
      }
      buffered.reset();
      try (Workbook workbook = WorkbookFactory.create(buffered)) {
        HeaderLocation header = findHeader(workbook);
        if (header == null) {
          issues.add(issue("HEADER_MISSING", null, null,
              "未找到正式电子图库表头：序号、代号、名称、材料、物料重要性分类、HSF风险分类、数量、单重/重量、备注"));
          return result(fileName, null, List.of(), issues);
        }
        List<ElectronicDrawingExcelParseResult.SourceNode> nodes = parseRows(header, issues);
        validateParents(nodes, issues);
        if (nodes.isEmpty() && issues.isEmpty()) {
          issues.add(issue("DETAIL_EMPTY", header.headerRow().getRowNum() + 2, null,
              "电子图库 Excel 没有 BOM 明细"));
        }
        return result(fileName, header.sheet().getSheetName(), nodes, issues);
      }
    } catch (EncryptedDocumentException exception) {
      issues.add(issue("FILE_ENCRYPTED", null, null, "Excel 已加密，无法解析"));
    } catch (IOException | RuntimeException exception) {
      issues.add(issue("FILE_INVALID", null, null, "Excel 文件损坏或格式不正确"));
    }
    return result(fileName, null, List.of(), issues);
  }

  private HeaderLocation findHeader(Workbook workbook) {
    for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
      Sheet sheet = workbook.getSheetAt(sheetIndex);
      int last = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROW_LIMIT - 1);
      for (int rowIndex = Math.max(0, sheet.getFirstRowNum()); rowIndex <= last; rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        Map<String, Integer> columns = headerColumns(row);
        if (columns.keySet().containsAll(REQUIRED_HEADERS)) {
          return new HeaderLocation(sheet, row, columns);
        }
      }
    }
    return null;
  }

  private Map<String, Integer> headerColumns(Row row) {
    Map<String, Integer> columns = new HashMap<>();
    if (row == null) return columns;
    for (int column = row.getFirstCellNum(); column >= 0 && column < row.getLastCellNum(); column++) {
      String label = normalizeHeader(text(row.getCell(column)));
      if (WEIGHT_HEADER.matcher(label).matches()) {
        columns.putIfAbsent("重量", column);
        continue;
      }
      if ("重量单位".equals(label) || "单重单位".equals(label)) {
        columns.putIfAbsent("重量单位", column);
        continue;
      }
      for (String required : REQUIRED_HEADERS) {
        if (required.equals(label)) {
          columns.putIfAbsent(required, column);
        }
      }
    }
    return columns;
  }

  private List<ElectronicDrawingExcelParseResult.SourceNode> parseRows(
      HeaderLocation header,
      List<ElectronicDrawingExcelParseResult.Issue> issues) {
    List<ElectronicDrawingExcelParseResult.SourceNode> nodes = new ArrayList<>();
    Set<String> sequences = new LinkedHashSet<>();
    boolean started = false;
    for (int rowIndex = header.headerRow().getRowNum() + 1;
         rowIndex <= header.sheet().getLastRowNum(); rowIndex++) {
      Row row = header.sheet().getRow(rowIndex);
      if (row == null || rowBlank(row)) continue;
      int sourceRow = rowIndex + 1;
      String sequence = value(row, header, "序号");
      if (!isSequence(sequence)) {
        if (started && !looksLikeBomDetail(row, header)) break;
        if (looksLikeBomDetail(row, header)) {
          issues.add(issue(sequence == null ? "SEQUENCE_REQUIRED" : "SEQUENCE_INVALID",
              sourceRow, sequence, sequence == null ? "BOM 明细缺少序号" : "BOM 序号格式不正确：" + sequence));
        }
        continue;
      }
      started = true;
      if (nodes.size() >= MAX_DETAIL_NODES) {
        issues.add(issue("DETAIL_LIMIT_EXCEEDED", sourceRow, sequence,
            "单个 BOM 明细不能超过 " + MAX_DETAIL_NODES + " 行"));
        break;
      }
      if (!sequences.add(sequence)) {
        issues.add(issue("SEQUENCE_DUPLICATED", sourceRow, sequence, "BOM 序号重复：" + sequence));
      }

      String drawingCode = value(row, header, "代号");
      String name = value(row, header, "名称");
      if (drawingCode == null) {
        issues.add(issue("DRAWING_CODE_REQUIRED", sourceRow, sequence, "代号不能为空"));
      }
      if (name == null) {
        issues.add(issue("NAME_REQUIRED", sourceRow, sequence, "名称不能为空"));
      }
      BigDecimal quantity = decimal(value(row, header, "数量"), "QUANTITY", sourceRow, sequence, issues, true);
      BigDecimal weight = decimal(value(row, header, "重量"), "WEIGHT", sourceRow, sequence, issues, false);
      String weightUnit = weightUnit(row, header, sourceRow, sequence, issues);
      nodes.add(new ElectronicDrawingExcelParseResult.SourceNode(
          sequence, parent(sequence), level(sequence), drawingCode, name,
          value(row, header, "材料"), value(row, header, "物料重要性分类"),
          value(row, header, "HSF风险分类"), quantity, weight, weightUnit,
          value(row, header, "备注"), sourceRow));
    }
    return nodes;
  }

  private String weightUnit(
      Row row, HeaderLocation header, int sourceRow, String sequence,
      List<ElectronicDrawingExcelParseResult.Issue> issues) {
    String headerValue = normalizeHeader(value(header.headerRow(), header, "重量"));
    var matcher = WEIGHT_HEADER.matcher(headerValue);
    String headerUnit = matcher.matches() ? matcher.group(1) : null;
    String rowUnit = value(row, header, "重量单位");
    String normalizedHeader = normalizeWeightUnit(headerUnit);
    String normalizedRow = normalizeWeightUnit(rowUnit);
    if ((headerUnit != null && normalizedHeader == null)
        || (rowUnit != null && normalizedRow == null)) {
      issues.add(issue("WEIGHT_UNIT_INVALID", sourceRow, sequence,
          "重量单位只支持 g（克）或 kg（千克），请核对源表"));
      return null;
    }
    if (normalizedHeader != null && normalizedRow != null
        && !normalizedHeader.equals(normalizedRow)) {
      issues.add(issue("WEIGHT_UNIT_CONFLICT", sourceRow, sequence,
          "重量表头与本行重量单位不一致，请核对源表"));
      return null;
    }
    // 2026-09-15 接入约定：未标单位的“单重/重量”为 g；明确标注时保留实际单位和原值。
    return normalizedRow != null ? normalizedRow : normalizedHeader != null ? normalizedHeader : "g";
  }

  private static String normalizeWeightUnit(String value) {
    return switch (normalizeHeader(value)) {
      case "G", "克" -> "g";
      case "KG", "千克", "公斤" -> "kg";
      default -> null;
    };
  }

  private void validateParents(
      List<ElectronicDrawingExcelParseResult.SourceNode> nodes,
      List<ElectronicDrawingExcelParseResult.Issue> issues) {
    Set<String> sequences = nodes.stream().map(ElectronicDrawingExcelParseResult.SourceNode::sourceSequence)
        .collect(java.util.stream.Collectors.toSet());
    for (ElectronicDrawingExcelParseResult.SourceNode node : nodes) {
      if (node.parentSourceSequence() != null && !sequences.contains(node.parentSourceSequence())) {
        issues.add(issue("PARENT_MISSING", node.sourceRowNumber(), node.sourceSequence(),
            "找不到直接父序号 " + node.parentSourceSequence() + "，当前序号：" + node.sourceSequence()));
      }
    }
  }

  private BigDecimal decimal(
      String raw,
      String field,
      int sourceRow,
      String sequence,
      List<ElectronicDrawingExcelParseResult.Issue> issues,
      boolean requiredPositive) {
    if (raw == null) {
      if (requiredPositive) {
        issues.add(issue(field + "_REQUIRED", sourceRow, sequence, "数量不能为空"));
      }
      return null;
    }
    try {
      BigDecimal value = new BigDecimal(raw.replace(",", ""));
      if (value.compareTo(BigDecimal.ZERO) < 0 || (requiredPositive && value.signum() == 0)) {
        issues.add(issue(field + "_INVALID", sourceRow, sequence,
            (requiredPositive ? "数量" : "重量") + "必须" + (requiredPositive ? "大于" : "大于等于") + " 0"));
      }
      return value.stripTrailingZeros();
    } catch (NumberFormatException exception) {
      issues.add(issue(field + "_INVALID", sourceRow, sequence,
          (requiredPositive ? "数量" : "重量") + "不是有效数字：" + raw));
      return null;
    }
  }

  private boolean looksLikeBomDetail(Row row, HeaderLocation header) {
    return value(row, header, "代号") != null
        || value(row, header, "名称") != null
        || isDecimal(value(row, header, "数量"));
  }

  private static boolean isDecimal(String raw) {
    if (raw == null) return false;
    try {
      new BigDecimal(raw.replace(",", ""));
      return true;
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private boolean rowBlank(Row row) {
    if (row == null) return true;
    for (int index = Math.max(0, row.getFirstCellNum()); index < row.getLastCellNum(); index++) {
      if (text(row.getCell(index)) != null) return false;
    }
    return true;
  }

  private String value(Row row, HeaderLocation header, String name) {
    Integer column = header.columns().get(name);
    return column == null || row == null ? null : text(row.getCell(column));
  }

  private String text(Cell cell) {
    if (cell == null) return null;
    String value = formatter.formatCellValue(cell);
    if (value == null) return null;
    String trimmed = value.replace('\u00A0', ' ').trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static boolean isSequence(String value) {
    return value != null && SEQUENCE.matcher(value).matches();
  }

  private static int level(String sequence) {
    return sequence.split("\\.").length;
  }

  private static String parent(String sequence) {
    int index = sequence.lastIndexOf('.');
    return index < 0 ? null : sequence.substring(0, index);
  }

  private static String normalizeHeader(String value) {
    return value == null ? "" : value.replace('（', '(').replace('）', ')')
        .replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  private static ElectronicDrawingExcelParseResult.Issue issue(
      String code, Integer row, String sequence, String message) {
    return new ElectronicDrawingExcelParseResult.Issue(code, row, sequence, message);
  }

  private static ElectronicDrawingExcelParseResult result(
      String fileName,
      String sheetName,
      List<ElectronicDrawingExcelParseResult.SourceNode> nodes,
      List<ElectronicDrawingExcelParseResult.Issue> issues) {
    return new ElectronicDrawingExcelParseResult(fileName, sheetName, nodes, issues);
  }

  private record HeaderLocation(Sheet sheet, Row headerRow, Map<String, Integer> columns) {
    private HeaderLocation {
      columns = Map.copyOf(new LinkedHashMap<>(columns));
    }
  }
}
