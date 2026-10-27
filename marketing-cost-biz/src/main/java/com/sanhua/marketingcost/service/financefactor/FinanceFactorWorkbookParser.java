package com.sanhua.marketingcost.service.financefactor;

import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Problem;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Scheme;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;

/** 仅处理商用方案表和板换固定价表；不把汇率附表或任意黄色单元格当作基价。 */
@Component
public class FinanceFactorWorkbookParser {
  public static final String VERSION = "FINANCE_BASE_V1";
  private final FactorCanonicalKeyService canonicalKeys;

  public FinanceFactorWorkbookParser(FactorCanonicalKeyService canonicalKeys) {
    this.canonicalKeys = canonicalKeys;
  }

  public FinanceFactorWorkbook parse(
      byte[] bytes, String fileName, String orgCode, String requestedSheet) {
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("财务基价文件不能为空");
    MaterialOrganization organization = MaterialOrganization.fromPriceOrgCode(orgCode);
    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var editable = FinanceFactorEditWorkbook.parseIfSupported(workbook, bytes, fileName, orgCode, requestedSheet, canonicalKeys);
      if (editable != null) {
        var problems = new ArrayList<>(editable.problems());
        validateDuplicateFactors(editable.sheetName(), editable.values(), problems);
        return new FinanceFactorWorkbook(editable.fileName(), editable.fileSha256(), editable.sheetName(),
            editable.parserVersion(), editable.schemes(), editable.values(), problems);
      }
      Header header = findHeader(workbook, organization, requestedSheet);
      FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      List<Problem> problems = new ArrayList<>();
      List<Value> values = new ArrayList<>();
      List<SchemeColumn> columns =
          organization == MaterialOrganization.PLATE
              ? boardColumns(header)
              : commercialColumns(header, evaluator);
      List<Scheme> schemes =
          columns.stream().map(c -> new Scheme(c.code(), c.label())).distinct().toList();
      for (int index = header.row() + 1; index <= header.sheet().getLastRowNum(); index++) {
        Row row = header.sheet().getRow(index);
        String name = text(cell(row, header.name()));
        String shortName = text(cell(row, header.shortName()));
        String unit = text(cell(row, header.unit()));
        if (name.isBlank() && shortName.isBlank() && unit.isBlank()) {
          continue;
        }
        // 商用原表下半部分是独立汇率附表，不属于材料基价表。
        if (organization == MaterialOrganization.COMMERCIAL
            && name.contains("汇率")
            && shortName.isBlank()
            && unit.isBlank()) break;
        if (name.isBlank()) name = shortName;
        if (shortName.isBlank()) shortName = name;
        String source = header.source() == null ? "平均价" : text(cell(row, header.source()));
        if (name.isBlank() || source.isBlank()) {
          problems.add(problem(header, index, header.name(), "因素名称和取价来源必填"));
          continue;
        }
        String key = canonicalKeys.build(source, shortName);
        for (SchemeColumn column : columns) {
          int selectedColumn = column.column();
          Cell selected = cell(row, selectedColumn);
          String basis = "SCHEME_VALUE";
          if (blank(selected) && organization == MaterialOrganization.COMMERCIAL) {
            selectedColumn = header.average();
            selected = cell(row, selectedColumn);
            basis = "CURRENT_WORKBOOK_PREVIOUS_MONTH_AVERAGE";
          }
          try {
            BigDecimal original = number(selected, evaluator);
            BigDecimal effective = original;
            String normalizedUnit = unit(unit);
            if (organization == MaterialOrganization.PLATE
                && isBoardSteel(shortName)
                && basis.equals("SCHEME_VALUE")) {
              if (original.compareTo(new BigDecimal("10000")) >= 0) {
                effective = original.movePointLeft(3);
                basis = "BOARD_FIXED_SUS_VALUE_DIVIDE_1000";
              } else {
                basis = "BOARD_FIXED_SUS_VALUE_ALREADY_PER_KG";
              }
              normalizedUnit = "CNY/KG";
            }
            validatePrice(effective);
            values.add(
                new Value(
                    key,
                    name,
                    shortName,
                    source,
                    column.code(),
                    column.condition(),
                    effective,
                    normalizedUnit,
                    true,
                    index + 1,
                    selectedColumn + 1,
                    new CellReference(index, selectedColumn).formatAsString(),
                    rawValue(selected, original),
                    original,
                    selected.getCellType() == CellType.FORMULA ? selected.getCellFormula() : null,
                    selected.getCellStyle().getDataFormatString(),
                    unit,
                    basis));
          } catch (RuntimeException error) {
            problems.add(problem(header, index, selectedColumn, error.getMessage()));
          }
        }
      }
      if (values.isEmpty())
        problems.add(new Problem(header.sheet().getSheetName(), null, "未找到有效基价行"));
      validateDuplicateFactors(header.sheet().getSheetName(), values, problems);
      return new FinanceFactorWorkbook(
          fileName,
          sha256(bytes),
          header.sheet().getSheetName(),
          VERSION,
          schemes,
          values,
          problems);
    } catch (IOException error) {
      throw new IllegalArgumentException("无法读取财务基价文件：" + fileName, error);
    }
  }

  private Header findHeader(
      Workbook workbook, MaterialOrganization organization, String requestedSheet) {
    List<Header> candidates = new ArrayList<>();
    for (Sheet sheet : workbook) {
      if (requestedSheet != null
          && !requestedSheet.isBlank()
          && !sheet.getSheetName().equals(requestedSheet)) continue;
      for (int index = 0; index <= Math.min(sheet.getLastRowNum(), 20); index++) {
        Row row = sheet.getRow(index);
        if (row == null) continue;
        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : row) headers.put(compact(text(cell)), cell.getColumnIndex());
        if (organization == MaterialOrganization.COMMERCIAL
            && headers.containsKey("基价名称")
            && headers.containsKey("基价代号")
            && headers.containsKey("计量单位")) {
          candidates.add(
              new Header(
                  sheet,
                  index,
                  headers.get("基价名称"),
                  headers.get("基价代号"),
                  headers.get("计量单位"),
                  findColumn(headers, "上月均价"),
                  null));
        }
        if (organization == MaterialOrganization.PLATE
            && headers.containsKey("简称")
            && headers.containsKey("取价来源")
            && headers.containsKey("单位")) {
          Integer name = headers.get("价表影响因素名称");
          candidates.add(
              new Header(
                  sheet,
                  index,
                  name == null ? headers.get("简称") : name,
                  headers.get("简称"),
                  headers.get("单位"),
                  findColumn(headers, "上月均价"),
                  headers.get("取价来源")));
        }
      }
    }
    if (candidates.size() != 1) {
      throw new IllegalArgumentException(
          "必须定位唯一财务基价表，请指定Sheet；匹配表："
              + candidates.stream().map(c -> c.sheet().getSheetName()).toList());
    }
    return candidates.getFirst();
  }

  private List<SchemeColumn> commercialColumns(Header header, FormulaEvaluator evaluator) {
    List<SchemeColumn> result = new ArrayList<>();
    Row copper = null;
    for (int index = header.row() + 1; index <= header.sheet().getLastRowNum(); index++) {
      Row row = header.sheet().getRow(index);
      if (canonicalKeys.build("平均价", text(cell(row, header.shortName()))).equals("AVG|1#CU")) {
        copper = row;
        break;
      }
    }
    if (copper == null)
      throw new IllegalArgumentException(header.sheet().getSheetName() + "缺少Cu因素，无法确定财务方案");
    for (Cell label : header.sheet().getRow(header.row())) {
      if (!compact(text(label)).startsWith("联动基价调整")) continue;
      int valueColumn = label.getColumnIndex();
      for (var region : header.sheet().getMergedRegions()) {
        if (region.isInRange(label)) valueColumn = region.getLastColumn();
      }
      // 方案按实际Cu值和明确行单位标识；历史“万元/吨”表头不参与数值转换。
      BigDecimal cu = number(cell(copper, valueColumn), evaluator);
      String copperUnit = unit(text(cell(copper, header.unit())));
      BigDecimal perTon =
          switch (copperUnit) {
            case "CNY/KG" -> cu.movePointRight(3);
            case "CNY/TON" -> cu;
            default -> throw new IllegalArgumentException("Cu方案单位必须为元/公斤或元/吨");
          };
      if (perTon.signum() <= 0) throw new IllegalArgumentException("方案Cu基价必须大于0");
      String amount = perTon.stripTrailingZeros().toPlainString();
      String code = "CU_" + amount.replace('.', '_');
      if (result.stream().anyMatch(c -> c.code().equals(code))) {
        throw new IllegalArgumentException("同Cu值出现多个无法区分的财务方案：" + amount);
      }
      result.add(new SchemeColumn(code, "铜价 " + amount + " 元/吨", valueColumn, Condition.GENERAL));
    }
    if (result.isEmpty()) throw new IllegalArgumentException("未识别到联动基价调整方案列");
    return result;
  }

  private List<SchemeColumn> boardColumns(Header header) {
    Integer nonTender = null, tender = null;
    for (Cell cell : header.sheet().getRow(header.row())) {
      String name = compact(text(cell));
      if (name.contains("非招标")) {
        if (nonTender != null) throw new IllegalArgumentException("板换表存在多个非招标基价列");
        nonTender = cell.getColumnIndex();
      } else if (name.contains("招标")) {
        if (tender != null) throw new IllegalArgumentException("板换表存在多个招标基价列");
        tender = cell.getColumnIndex();
      }
    }
    if (nonTender == null || tender == null)
      throw new IllegalArgumentException("板换表必须包含非招标和招标固定基价列");
    return List.of(
        new SchemeColumn("BOARD", "板换固定基价", nonTender, Condition.NON_TENDER),
        new SchemeColumn("BOARD", "板换固定基价", tender, Condition.TENDER));
  }

  private void validateDuplicateFactors(String sheetName, List<Value> values, List<Problem> problems) {
    Map<String, Value> byIdentity = new LinkedHashMap<>();
    for (Value value : values) {
      String key = value.canonicalKey() + "|" + value.schemeCode() + "|" + value.condition();
      Value previous = byIdentity.putIfAbsent(key, value);
      if (previous != null
          && (previous.price().compareTo(value.price()) != 0
              || !previous.unit().equals(value.unit())
              || previous.taxIncluded() != value.taxIncluded())) {
        problems.add(
            new Problem(
                sheetName,
                value.cellRef(),
                "同一因素/方案/条件存在冲突值，与" + previous.cellRef() + "不一致：" + value.canonicalKey()));
      }
    }
  }

  static BigDecimal number(Cell cell, FormulaEvaluator evaluator) {
    if (blank(cell)) throw new IllegalArgumentException("方案和本表上月均价均缺值");
    if (cell.getCellType() == CellType.NUMERIC) {
      return new BigDecimal(
          rawValue(cell, new BigDecimal(NumberToTextConverter.toText(cell.getNumericCellValue()))));
    }
    if (cell.getCellType() == CellType.FORMULA) {
      var result = evaluator.evaluate(cell);
      if (result != null && result.getCellType() == CellType.NUMERIC) {
        return new BigDecimal(NumberToTextConverter.toText(result.getNumberValue()));
      }
      throw new IllegalArgumentException("基价公式未得到数值：" + cell.getCellFormula());
    }
    if (cell.getCellType() == CellType.STRING) {
      try {
        return new BigDecimal(cell.getStringCellValue().trim().replace(",", ""));
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("基价不是数值：" + cell.getStringCellValue());
      }
    }
    throw new IllegalArgumentException("基价单元格类型无效：" + cell.getCellType());
  }

  static String unit(String value) {
    return switch (compact(value).toUpperCase(java.util.Locale.ROOT)) {
      case "元/公斤", "元/千克", "公斤", "千克", "KG", "CNY/KG" -> "CNY/KG";
      case "元/吨", "吨", "CNY/TON" -> "CNY/TON";
      case "元/克", "克", "CNY/G" -> "CNY/G";
      default -> throw new IllegalArgumentException("未确认的因素单位：" + value);
    };
  }

  private static boolean isBoardSteel(String shortName) {
    return compact(shortName).toUpperCase(java.util.Locale.ROOT).matches("(?:SUS)?(?:304|316).*");
  }

  private static int findColumn(Map<String, Integer> headers, String fragment) {
    List<Integer> columns =
        headers.entrySet().stream()
            .filter(e -> e.getKey().contains(fragment))
            .map(Map.Entry::getValue)
            .toList();
    if (columns.size() != 1) throw new IllegalArgumentException("无法唯一定位表头：" + fragment);
    return columns.getFirst();
  }

  private static Problem problem(Header header, int row, int column, String message) {
    return new Problem(
        header.sheet().getSheetName(), new CellReference(row, column).formatAsString(), message);
  }

  private static Cell cell(Row row, int column) {
    return row == null ? null : row.getCell(column);
  }

  private static boolean blank(Cell cell) {
    return cell == null
        || cell.getCellType() == CellType.BLANK
        || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
  }

  private static String text(Cell cell) {
    return cell != null && cell.getCellType() == CellType.STRING
        ? cell.getStringCellValue().trim()
        : "";
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "").replace('（', '(').replace('）', ')');
  }

  static String rawValue(Cell cell, BigDecimal evaluated) {
    if (cell instanceof XSSFCell xssf
        && xssf.getRawValue() != null
        && (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA))
      return xssf.getRawValue();
    return cell.getCellType() == CellType.STRING
        ? cell.getStringCellValue()
        : evaluated.toPlainString();
  }

  static void validatePrice(BigDecimal price) {
    if (price.signum() < 0) throw new IllegalArgumentException("财务基价不能为负数");
    if (price.stripTrailingZeros().scale() > 16 || price.precision() - price.scale() > 18)
      throw new IllegalArgumentException("有效基价超出精确存储范围，不允许静默舍入");
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Header(
      Sheet sheet, int row, int name, int shortName, int unit, int average, Integer source) {}

  private record SchemeColumn(String code, String label, int column, Condition condition) {}
}
