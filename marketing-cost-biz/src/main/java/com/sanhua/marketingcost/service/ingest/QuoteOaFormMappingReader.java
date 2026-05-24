package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.util.StringUtils;

public class QuoteOaFormMappingReader {
  static final String MAPPING_SHEET = "解析字段映射";

  List<QuoteOaFormFieldMapping> read(
      Workbook workbook, DataFormatter formatter, FormulaEvaluator evaluator, QuoteParsedExcel parsed) {
    Sheet sheet = workbook.getSheet(MAPPING_SHEET);
    if (sheet == null) {
      parsed.errors.add(new QuoteValidationError(MAPPING_SHEET, "MAPPING_SHEET_REQUIRED", "缺少解析字段映射 Sheet"));
      return List.of();
    }

    Map<String, Integer> columns = readColumns(sheet.getRow(0), formatter, evaluator);
    String[] required = {"scope", "field_code", "field_name", "source_range", "target_table"};
    for (String column : required) {
      if (!columns.containsKey(column)) {
        parsed
            .errors
            .add(new QuoteValidationError(MAPPING_SHEET + "." + column, "MAPPING_COLUMN_REQUIRED", "解析字段映射缺少必需列"));
      }
    }
    if (!parsed.errors.isEmpty()) {
      return List.of();
    }

    List<QuoteOaFormFieldMapping> mappings = new ArrayList<>();
    for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      String scope = cell(row, columns, "scope", formatter, evaluator);
      String fieldCode = cell(row, columns, "field_code", formatter, evaluator);
      String fieldName = cell(row, columns, "field_name", formatter, evaluator);
      String sourceRange = cell(row, columns, "source_range", formatter, evaluator);
      String targetTable = cell(row, columns, "target_table", formatter, evaluator);
      if (!StringUtils.hasText(scope)
          && !StringUtils.hasText(fieldCode)
          && !StringUtils.hasText(sourceRange)) {
        continue;
      }
      if (!StringUtils.hasText(scope)
          || !StringUtils.hasText(fieldCode)
          || !StringUtils.hasText(sourceRange)) {
        parsed
            .errors
            .add(
                new QuoteValidationError(
                    MAPPING_SHEET, "MAPPING_ROW_INVALID", "解析字段映射行缺少 scope/field_code/source_range", rowIndex + 1));
        continue;
      }
      mappings.add(
          new QuoteOaFormFieldMapping(
              scope.trim().toUpperCase(Locale.ROOT),
              fieldCode.trim(),
              trimToNull(fieldName),
              sourceRange.trim(),
              trimToNull(targetTable)));
    }
    return mappings;
  }

  private Map<String, Integer> readColumns(
      Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    if (row == null) {
      return columns;
    }
    for (Cell cell : row) {
      String value = formatter.formatCellValue(cell, evaluator);
      if (StringUtils.hasText(value)) {
        columns.put(value.trim(), cell.getColumnIndex());
      }
    }
    return columns;
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
    String value = formatter.formatCellValue(row.getCell(column), evaluator);
    return trimToNull(value);
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
