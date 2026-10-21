package com.sanhua.marketingcost.service.platematerial;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Parses the financial worksheet without consulting or mutating business data. */
@Component
public class PlateMaterialWorkbookParser {
  public static final String SHEET = "提供财务";
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final int MAX_ROWS = 20_000;

  public record SourceRow(int excelRow, String model, PlateMaterial material) {}
  public record RowError(int excelRow, String message) {}
  public record Duplicate(int excelRow, int firstExcelRow, String model) {}
  public record WorkbookData(String fileName, String sha256, List<SourceRow> rows,
      List<RowError> errors, List<Duplicate> duplicates) {}

  public WorkbookData parse(byte[] bytes, String originalName) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("请上传非空且不超过 10 MB 的 xlsx 文件");
    }
    String fileName = fileName(originalName);
    List<RowError> errors = new ArrayList<>();
    List<Duplicate> duplicates = new ArrayList<>();
    var rows = new LinkedHashMap<String, SourceRow>();
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheet(SHEET);
      if (sheet == null) throw new IllegalArgumentException("缺少工作表「提供财务」");
      if (sheet.getLastRowNum() >= MAX_ROWS) throw new IllegalArgumentException("提供财务工作表最多支持 20,000 行");
      Row header = sheet.getRow(1);
      if (header == null || !"产品型号".equals(text(header.getCell(1)))
          || !"产品应用材质".equals(text(header.getCell(2)))) {
        throw new IllegalArgumentException("提供财务第 2 行 B/C 列表头应为「产品型号」「产品应用材质」");
      }
      List<Integer> pendingBlankRows = new ArrayList<>();
      boolean footerStarted = false;
      for (int index = 2; index <= sheet.getLastRowNum(); index++) {
        Row row = sheet.getRow(index);
        int excelRow = index + 1;
        if (blank(row)) {
          pendingBlankRows.add(excelRow);
          continue;
        }
        if (footer(row)) {
          footerStarted = true;
          pendingBlankRows.clear();
          continue;
        }
        for (int blankRow : pendingBlankRows) errors.add(new RowError(blankRow, "业务数据中间存在空行"));
        pendingBlankRows.clear();
        if (footerStarted) {
          errors.add(new RowError(excelRow, "尾注之后不能再出现业务数据"));
          continue;
        }
        try {
          String model = PlateMaterialModel.require(text(row.getCell(1)));
          PlateMaterial material = PlateMaterial.parse(text(row.getCell(2)));
          var source = new SourceRow(excelRow, model, material);
          var previous = rows.putIfAbsent(model, source);
          if (previous == null) continue;
          if (previous.material() == material) {
            duplicates.add(new Duplicate(excelRow, previous.excelRow(), model));
          } else {
            errors.add(new RowError(excelRow, "型号与第 " + previous.excelRow() + " 行重复，但板片材质不同：" + model));
          }
        } catch (IllegalArgumentException ex) {
          errors.add(new RowError(excelRow, ex.getMessage()));
        }
      }
      if (rows.isEmpty() && errors.isEmpty()) errors.add(new RowError(3, "没有可导入的型号—板片材质数据"));
    } catch (IOException ex) {
      throw new IllegalArgumentException("无法读取 xlsx 工作簿", ex);
    }
    return new WorkbookData(fileName, sha256(bytes), List.copyOf(rows.values()),
        List.copyOf(errors), List.copyOf(duplicates));
  }

  private static boolean blank(Row row) {
    if (row == null) return true;
    for (Cell cell : row) {
      if (cell.getCellType() != CellType.BLANK
          && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) return false;
    }
    return true;
  }

  private static boolean footer(Row row) {
    if (!empty(row.getCell(1)) || !empty(row.getCell(2))) return false;
    Cell first = row.getCell(0);
    if (first == null || first.getCellType() != CellType.STRING) return false;
    String label = first.getStringCellValue().strip();
    return label.startsWith("注：") || label.startsWith("编制：");
  }

  private static boolean empty(Cell cell) {
    return cell == null || cell.getCellType() == CellType.BLANK
        || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
  }

  private static String text(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return null;
    if (cell.getCellType() != CellType.STRING) throw new IllegalArgumentException("型号和板片材质必须是文本，不能使用公式或数值");
    return cell.getStringCellValue();
  }

  private static String fileName(String name) {
    if (name == null) throw new IllegalArgumentException("来源文件名不能为空");
    String safe = name.replace('\\', '/');
    safe = safe.substring(safe.lastIndexOf('/') + 1);
    if (safe.isBlank() || safe.length() > 255 || !safe.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
      throw new IllegalArgumentException("来源文件名必须为不超过 255 字符的 xlsx 文件名");
    }
    return safe;
  }

  static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }
}
