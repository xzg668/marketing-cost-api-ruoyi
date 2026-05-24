package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExtraFeeRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteExtraFieldRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import com.sanhua.marketingcost.enums.QuoteExtraFeeCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.springframework.util.StringUtils;

public class QuoteOaFormExcelParser {
  static final String OA_FORM_SHEET = "OA原始表单";

  private static final String SOURCE_SYSTEM_EXCEL_TEMPLATE = "EXCEL_TEMPLATE";
  private static final String SCOPE_HEADER = "HEADER";
  private static final String SCOPE_ITEM = "ITEM";

  private final QuoteOaFormMappingReader mappingReader;

  public QuoteOaFormExcelParser(QuoteOaFormMappingReader mappingReader) {
    this.mappingReader = mappingReader;
  }

  boolean supports(Workbook workbook) {
    return workbook.getSheet(OA_FORM_SHEET) != null;
  }

  QuoteParsedExcel parse(Workbook workbook, String fileName, DataFormatter formatter, FormulaEvaluator evaluator) {
    QuoteParsedExcel parsed = new QuoteParsedExcel(fileName);
    Sheet formSheet = workbook.getSheet(OA_FORM_SHEET);
    if (formSheet == null) {
      parsed.errors.add(new QuoteValidationError(OA_FORM_SHEET, "OA_FORM_SHEET_REQUIRED", "缺少 OA原始表单 Sheet"));
      return parsed;
    }

    List<QuoteOaFormFieldMapping> mappings = mappingReader.read(workbook, formatter, evaluator, parsed);
    if (!parsed.errors.isEmpty()) {
      return parsed;
    }

    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceSystem(SOURCE_SYSTEM_EXCEL_TEMPLATE);
    request.setVersion("1");
    request.setHeader(new QuoteIngestHeaderRequest());
    request.setRawPayload(
        Map.of(
            "fileName", nullToEmpty(fileName),
            "oaFormSheet", OA_FORM_SHEET,
            "mappingSheet", QuoteOaFormMappingReader.MAPPING_SHEET));

    Map<String, String> headerValues = readHeader(formSheet, mappings, formatter, evaluator, request, parsed);
    fillMissingApplyDate(request, headerValues);
    String headerBusinessType = headerValues.get("businessType");
    readItems(formSheet, mappings, formatter, evaluator, request, parsed, headerBusinessType);

    if (!StringUtils.hasText(request.getSourceType())) {
      request.setSourceType("EXCEL");
    }
    if (!StringUtils.hasText(request.getSourceSystem())) {
      request.setSourceSystem(SOURCE_SYSTEM_EXCEL_TEMPLATE);
    }
    if (!StringUtils.hasText(request.getExternalFormNo())) {
      request.setExternalFormNo(request.getOaNo());
    }
    if (StringUtils.hasText(request.getOaNo())) {
      request.setIdempotencyKey("EXCEL:" + request.getOaNo().trim() + ":1");
    }

    parsed.requests.put(requestKey(request), request);
    parsed.formCount = 1;
    parsed.itemCount = request.getItems().size();
    parsed.feeCount =
        request.getExtraFees().size()
            + request.getItems().stream().mapToInt(item -> item.getExtraFees().size()).sum();
    return parsed;
  }

  private Map<String, String> readHeader(
      Sheet sheet,
      List<QuoteOaFormFieldMapping> mappings,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      QuoteIngestRequest request,
      QuoteParsedExcel parsed) {
    Map<String, String> values = new LinkedHashMap<>();
    for (QuoteOaFormFieldMapping mapping : mappings) {
      if (!SCOPE_HEADER.equals(mapping.getScope())) {
        continue;
      }
      SourceRange sourceRange = SourceRange.parse(mapping.getSourceRange());
      String value = constantValue(mapping.getSourceRange());
      if (value == null && sourceRange == null) {
        parsed
            .errors
            .add(new QuoteValidationError(mapping.getFieldCode(), "MAPPING_SOURCE_RANGE_INVALID", "字段映射 source_range 无法解析"));
        continue;
      }
      if (value == null) {
        value = cellValue(sheet, sourceRange.firstRow, sourceRange.firstColumn, formatter, evaluator);
      }
      values.put(mapping.getFieldCode(), value);
      if (!StringUtils.hasText(value)) {
        continue;
      }

      if (isFeeField(mapping.getFieldCode(), mapping.getFieldName())) {
        request.getExtraFees().add(toFee(mapping, value, mapping.getSourceRange()));
      } else if (!QuoteIngestFieldBinder.applyRequestField(request, mapping.getFieldCode(), value)
          && !QuoteIngestFieldBinder.applyHeaderField(request.getHeader(), mapping.getFieldCode(), value)) {
        request.getExtraFields().add(toExtraField(mapping, value, mapping.getSourceRange()));
      }
    }
    return values;
  }

  private void readItems(
      Sheet sheet,
      List<QuoteOaFormFieldMapping> mappings,
      DataFormatter formatter,
      FormulaEvaluator evaluator,
      QuoteIngestRequest request,
      QuoteParsedExcel parsed,
      String headerBusinessType) {
    List<QuoteOaFormFieldMapping> itemMappings =
        mappings.stream().filter(mapping -> SCOPE_ITEM.equals(mapping.getScope())).toList();
    if (itemMappings.isEmpty()) {
      return;
    }

    List<ItemColumnMapping> columns = new ArrayList<>();
    for (QuoteOaFormFieldMapping mapping : itemMappings) {
      SourceRange range = SourceRange.parse(mapping.getSourceRange());
      if (range == null) {
        parsed
            .errors
            .add(new QuoteValidationError(mapping.getFieldCode(), "MAPPING_SOURCE_RANGE_INVALID", "字段映射 source_range 无法解析"));
        continue;
      }
      columns.add(new ItemColumnMapping(mapping, range));
    }
    if (columns.isEmpty()) {
      return;
    }

    int firstRow = columns.stream().mapToInt(column -> column.range.firstRow).min().orElse(0);
    int lastRow = columns.stream().mapToInt(column -> column.range.lastRow).max().orElse(firstRow);
    columns.sort(Comparator.comparingInt(column -> column.range.firstColumn));

    for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
      QuoteIngestItemRequest item = new QuoteIngestItemRequest();
      item.setExternalLineId(OA_FORM_SHEET + "!R" + (rowIndex + 1));
      boolean hasValue = false;
      for (ItemColumnMapping column : columns) {
        int relativeRow = rowIndex - column.range.firstRow;
        if (relativeRow < 0 || rowIndex > column.range.lastRow) {
          continue;
        }
        String sourcePath =
            OA_FORM_SHEET + "!" + CellReference.convertNumToColString(column.range.firstColumn) + (rowIndex + 1);
        String value = cellValue(sheet, rowIndex, column.range.firstColumn, formatter, evaluator);
        if (!StringUtils.hasText(value)) {
          continue;
        }
        hasValue = true;
        if (isFeeField(column.mapping.getFieldCode(), column.mapping.getFieldName())) {
          item.getExtraFees().add(toFee(column.mapping, value, sourcePath));
        } else if (!QuoteIngestFieldBinder.applyItemField(item, column.mapping.getFieldCode(), value)) {
          item.getExtraFields().add(toExtraField(column.mapping, value, sourcePath));
        }
      }
      if (!hasValue || isBlankItem(item)) {
        continue;
      }
      if (!StringUtils.hasText(item.getBusinessType()) && StringUtils.hasText(headerBusinessType)) {
        item.setBusinessType(headerBusinessType);
      }
      request.getItems().add(item);
    }
  }

  private void fillMissingApplyDate(QuoteIngestRequest request, Map<String, String> headerValues) {
    if (request.getHeader() == null || StringUtils.hasText(request.getHeader().getApplyDate())) {
      return;
    }
    String applyDate =
        dateOnly(
            coalesce(
                headerValues.get("applyDate"),
                headerValues.get("applyDateTime"),
                headerValues.get("replyDateRequiredBySales")));
    if (!StringUtils.hasText(applyDate)) {
      applyDate = dateFromOaNo(request.getOaNo());
    }
    if (StringUtils.hasText(applyDate)) {
      request.getHeader().setApplyDate(applyDate);
    }
  }

  private String dateOnly(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    int blank = normalized.indexOf(' ');
    return blank > 0 ? normalized.substring(0, blank) : normalized;
  }

  private String dateFromOaNo(String oaNo) {
    String normalized = trimToNull(oaNo);
    if (normalized == null) {
      return null;
    }
    for (String part : normalized.split("-")) {
      if (part.matches("\\d{8}")) {
        return part.substring(0, 4) + "-" + part.substring(4, 6) + "-" + part.substring(6, 8);
      }
    }
    return null;
  }

  private QuoteExtraFieldRequest toExtraField(
      QuoteOaFormFieldMapping mapping, String value, String sourcePath) {
    QuoteExtraFieldRequest field = new QuoteExtraFieldRequest();
    field.setFieldCode(mapping.getFieldCode());
    field.setFieldName(mapping.getFieldName());
    field.setFieldValue(value);
    field.setValueType(valueType(value));
    field.setSourceFieldName(mapping.getFieldName());
    field.setSourceFieldPath(sourcePath);
    return field;
  }

  private QuoteExtraFeeRequest toFee(QuoteOaFormFieldMapping mapping, String value, String sourcePath) {
    QuoteExtraFeeRequest fee = new QuoteExtraFeeRequest();
    fee.setFeeCode(mapping.getFieldCode());
    fee.setFeeName(mapping.getFieldName());
    fee.setFeeCategory(feeCategory(mapping.getFieldCode(), mapping.getFieldName()));
    fee.setAmount(value);
    fee.setUnit(unit(mapping.getFieldName()));
    fee.setSourceFieldName(mapping.getFieldName());
    fee.setSourceFieldPath(sourcePath);
    return fee;
  }

  private boolean isFeeField(String fieldCode, String fieldName) {
    String code = nullToEmpty(fieldCode).toLowerCase(Locale.ROOT);
    String name = nullToEmpty(fieldName);
    if (code.contains("bearer") || name.contains("承担")) {
      return false;
    }
    return code.contains("fixture")
        || code.contains("mold")
        || code.contains("toolcost")
        || code.contains("tooling")
        || code.contains("certificationfee")
        || code.contains("equipmentfee")
        || name.contains("工装")
        || name.contains("模具")
        || name.contains("刀具")
        || name.contains("认证费")
        || name.contains("设备费");
  }

  private String feeCategory(String fieldCode, String fieldName) {
    String text = nullToEmpty(fieldCode) + " " + nullToEmpty(fieldName);
    if (text.contains("认证")) {
      return QuoteExtraFeeCategory.CERTIFICATION.getCode();
    }
    if (text.contains("设备")) {
      return QuoteExtraFeeCategory.EQUIPMENT.getCode();
    }
    if (text.contains("刀具") || text.toLowerCase(Locale.ROOT).contains("toolcost")) {
      return QuoteExtraFeeCategory.CUTTER.getCode();
    }
    if (text.contains("模具") || text.toLowerCase(Locale.ROOT).contains("mold")) {
      return QuoteExtraFeeCategory.MOLD.getCode();
    }
    if (text.contains("工装") || text.toLowerCase(Locale.ROOT).contains("fixture")) {
      return QuoteExtraFeeCategory.TOOLING.getCode();
    }
    return QuoteExtraFeeCategory.OTHER.getCode();
  }

  private String unit(String fieldName) {
    if (StringUtils.hasText(fieldName) && fieldName.contains("万元")) {
      return "万元";
    }
    if (StringUtils.hasText(fieldName) && fieldName.contains("元/只")) {
      return "元/只";
    }
    return "元";
  }

  private String cellValue(
      Sheet sheet, int rowIndex, int columnIndex, DataFormatter formatter, FormulaEvaluator evaluator) {
    Cell cell = cellOrMergedTopLeft(sheet, rowIndex, columnIndex);
    if (cell == null) {
      return null;
    }
    String value = formatter.formatCellValue(cell, evaluator);
    return trimToNull(value);
  }

  private String constantValue(String sourceRange) {
    if (!StringUtils.hasText(sourceRange)) {
      return null;
    }
    String value = sourceRange.trim();
    String upper = value.toUpperCase(Locale.ROOT);
    if (upper.startsWith("CONST:")) {
      return trimToNull(value.substring("CONST:".length()));
    }
    if (upper.startsWith("VALUE:")) {
      return trimToNull(value.substring("VALUE:".length()));
    }
    return null;
  }

  private Cell cellOrMergedTopLeft(Sheet sheet, int rowIndex, int columnIndex) {
    Cell cell = sheet.getRow(rowIndex) == null ? null : sheet.getRow(rowIndex).getCell(columnIndex);
    if (cell != null) {
      return cell;
    }
    for (CellRangeAddress region : sheet.getMergedRegions()) {
      if (region.isInRange(rowIndex, columnIndex)) {
        return sheet.getRow(region.getFirstRow()) == null
            ? null
            : sheet.getRow(region.getFirstRow()).getCell(region.getFirstColumn());
      }
    }
    return null;
  }

  private boolean isBlankItem(QuoteIngestItemRequest item) {
    return !StringUtils.hasText(item.getMaterialNo())
        && !StringUtils.hasText(item.getSunlModel())
        && !StringUtils.hasText(item.getProductName())
        && !StringUtils.hasText(item.getCustomerCode())
        && item.getSeq() == null;
  }

  private String valueType(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return "TEXT";
    }
    if (normalized.matches("-?\\d+(,\\d{3})*(\\.\\d+)?")) {
      return "NUMBER";
    }
    if (normalized.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}.*")) {
      return "DATE";
    }
    return "TEXT";
  }

  private String requestKey(QuoteIngestRequest request) {
    return StringUtils.hasText(request.getOaNo()) ? request.getOaNo().trim() : "$OA_FORM";
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String coalesce(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record ItemColumnMapping(QuoteOaFormFieldMapping mapping, SourceRange range) {}

  private static final class SourceRange {
    final int firstRow;
    final int lastRow;
    final int firstColumn;

    private SourceRange(int firstRow, int lastRow, int firstColumn) {
      this.firstRow = firstRow;
      this.lastRow = lastRow;
      this.firstColumn = firstColumn;
    }

    static SourceRange parse(String sourceRange) {
      if (!StringUtils.hasText(sourceRange)) {
        return null;
      }
      String ref = sourceRange.trim();
      int bang = ref.indexOf('!');
      if (bang >= 0) {
        ref = ref.substring(bang + 1);
      }
      ref = ref.replace("'", "").replace("$", "");
      String[] parts = ref.split(":");
      try {
        CellReference first = new CellReference(parts[0]);
        CellReference last = parts.length > 1 ? new CellReference(parts[1]) : first;
        return new SourceRange(
            Math.min(first.getRow(), last.getRow()),
            Math.max(first.getRow(), last.getRow()),
            first.getCol());
      } catch (IllegalArgumentException ex) {
        return null;
      }
    }
  }
}
