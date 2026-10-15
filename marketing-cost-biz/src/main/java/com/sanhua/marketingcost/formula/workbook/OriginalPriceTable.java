package com.sanhua.marketingcost.formula.workbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.*;

/** 联动和区间原稿共用主表契约，保留底层值而非Excel显示值。 */
public final class OriginalPriceTable {
  private OriginalPriceTable() {}

  private static final Set<String> MAIN_HEADERS =
      Set.of("组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税");

  public static Header mainSheet(Workbook workbook) {
    Sheet importData = workbook.getSheet("ImportData");
    if (importData != null) {
      Header header = header(importData, MAIN_HEADERS);
      if (header == null) throw new IllegalArgumentException("ImportData主表表头不完整");
      return header;
    }
    List<Header> candidates = new ArrayList<>();
    for (Sheet sheet : workbook) {
      Header header = header(sheet, MAIN_HEADERS);
      if (header != null) candidates.add(header);
    }
    if (candidates.size() != 1)
      throw new IllegalArgumentException("必须具有唯一物料主价表，实际=" + candidates.size());
    return candidates.getFirst();
  }

  public static Header header(Sheet sheet, Set<String> required) {
    for (int index = sheet.getFirstRowNum();
        index <= Math.min(sheet.getLastRowNum(), 24);
        index++) {
      Row row = sheet.getRow(index);
      if (row == null) continue;
      Map<String, Integer> columns = new LinkedHashMap<>();
      for (Cell cell : row)
        columns.put(text(cell).replace(" ", "").replace("\n", ""), cell.getColumnIndex());
      if (columns.keySet().containsAll(required)) return new Header(sheet, index, columns);
    }
    return null;
  }

  public static Cell at(Row row, Header header, String name) {
    Integer column = header.columns().get(name);
    return row == null || column == null ? null : row.getCell(column);
  }

  public static String text(Cell cell) {
    if (cell == null) return "";
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    if (type == CellType.STRING) return cell.getStringCellValue().trim();
    if (type == CellType.NUMERIC)
      return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    if (type == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
    return "";
  }

  public static String materialCode(Cell cell) {
    if (cell != null
        && (cell.getCellType() == CellType.ERROR
            || (cell.getCellType() == CellType.FORMULA
                && cell.getCachedFormulaResultType() == CellType.ERROR))) {
      throw new IllegalArgumentException(
          cell.getSheet().getSheetName() + "!" + cell.getAddress() + "：料号格为错误值");
    }
    return text(cell);
  }

  public static BigDecimal numeric(Cell cell) {
    if (cell == null) return null;
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    if (type == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
    if (type == CellType.STRING) {
      try {
        return new BigDecimal(cell.getStringCellValue().trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  public static boolean taxIncluded(Cell cell) {
    if (cell != null && cell.getCellType() == CellType.BOOLEAN) return cell.getBooleanCellValue();
    return switch (text(cell).toUpperCase(java.util.Locale.ROOT)) {
      case "TRUE", "是", "1" -> true;
      case "FALSE", "否", "0" -> false;
      default -> throw new IllegalArgumentException("主表是否含税为空或无法识别");
    };
  }

  public record Header(Sheet sheet, int row, Map<String, Integer> columns) {}
}
