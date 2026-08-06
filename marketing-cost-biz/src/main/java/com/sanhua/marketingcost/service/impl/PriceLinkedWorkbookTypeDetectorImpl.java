package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import com.sanhua.marketingcost.service.PriceLinkedWorkbookTypeDetector;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

@Component
public class PriceLinkedWorkbookTypeDetectorImpl implements PriceLinkedWorkbookTypeDetector {

  private static final Set<String> STANDARD_HEADERS = normalizedHeaders(
      "物料代码", "供应商名称", "供应商代码", "单价", "是否含税");
  private static final Set<String> TYPE2_HEADERS = normalizedHeaders(
      "U9代码", "供应商名称", "现含税价");

  @Override
  public PriceLinkedWorkbookDetectionResult detect(InputStream input) {
    if (input == null) {
      throw new IllegalArgumentException("Excel 流不能为空");
    }

    List<String> standardCandidates = new ArrayList<>();
    List<String> type2Candidates = new ArrayList<>();
    try (Workbook workbook = WorkbookFactory.create(input)) {
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
        if (isHidden(workbook, sheetIndex)) {
          continue;
        }
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        SheetMatch match = inspectSheet(sheet, formatter);
        if (match.standard()) {
          standardCandidates.add(sheet.getSheetName());
        }
        if (match.type2()) {
          type2Candidates.add(sheet.getSheetName());
        }
      }
    } catch (IOException | RuntimeException ex) {
      throw new IllegalArgumentException("无法读取 Excel 工作簿: " + ex.getMessage(), ex);
    }

    PriceLinkedWorkbookType type = resolveType(standardCandidates, type2Candidates);
    return new PriceLinkedWorkbookDetectionResult(
        type,
        standardCandidates,
        type2Candidates,
        buildMessage(type, standardCandidates, type2Candidates));
  }

  private SheetMatch inspectSheet(Sheet sheet, DataFormatter formatter) {
    boolean standard = false;
    boolean type2 = false;
    for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null || row.getLastCellNum() < 0) {
        continue;
      }
      Set<String> values = normalizedCellValues(row, formatter);
      standard = standard || values.containsAll(STANDARD_HEADERS);
      type2 = type2 || values.containsAll(TYPE2_HEADERS);
      if (standard && type2) {
        break;
      }
    }
    return new SheetMatch(standard, type2);
  }

  private Set<String> normalizedCellValues(Row row, DataFormatter formatter) {
    Set<String> values = new HashSet<>();
    for (int columnIndex = row.getFirstCellNum(); columnIndex < row.getLastCellNum();
        columnIndex++) {
      if (columnIndex < 0) {
        continue;
      }
      Cell cell = row.getCell(columnIndex);
      String normalized = normalizeHeader(formatter.formatCellValue(cell));
      if (!normalized.isEmpty()) {
        values.add(normalized);
      }
    }
    return values;
  }

  private PriceLinkedWorkbookType resolveType(
      List<String> standardCandidates, List<String> type2Candidates) {
    if (standardCandidates.size() > 1 || type2Candidates.size() > 1) {
      return PriceLinkedWorkbookType.AMBIGUOUS;
    }
    if (type2Candidates.size() == 1) {
      return PriceLinkedWorkbookType.TYPE2;
    }
    if (standardCandidates.size() == 1) {
      return PriceLinkedWorkbookType.STANDARD;
    }
    return PriceLinkedWorkbookType.UNKNOWN;
  }

  private String buildMessage(
      PriceLinkedWorkbookType type,
      List<String> standardCandidates,
      List<String> type2Candidates) {
    return switch (type) {
      case STANDARD -> "识别到一个标准导入 Sheet";
      case TYPE2 -> "识别到一个类型 2 业务计算 Sheet";
      case UNKNOWN -> "未识别到标准导入或类型 2 业务计算 Sheet";
      case AMBIGUOUS -> "检测到多个同类候选 Sheet，标准候选="
          + standardCandidates + "，类型2候选=" + type2Candidates;
    };
  }

  private boolean isHidden(Workbook workbook, int sheetIndex) {
    return workbook.isSheetHidden(sheetIndex) || workbook.isSheetVeryHidden(sheetIndex);
  }

  private static Set<String> normalizedHeaders(String... headers) {
    Set<String> normalized = new HashSet<>();
    for (String header : headers) {
      normalized.add(normalizeHeader(header));
    }
    return Set.copyOf(normalized);
  }

  private static String normalizeHeader(String raw) {
    if (raw == null) {
      return "";
    }
    return Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .replace("\uFEFF", "")
        .strip()
        .replaceAll("\\s+", "")
        .toUpperCase(Locale.ROOT);
  }

  private record SheetMatch(boolean standard, boolean type2) {
  }
}
