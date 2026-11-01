package com.sanhua.marketingcost.service.pricing.inquiry;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.stereotype.Component;

/** Template adapter only: formula expansion remains owned by WorkbookFormulaCompiler. */
@Component
public final class InquiryWorkbookParser {
  public static final int MAX_BYTES = 10 * 1024 * 1024;

  public record Factor(String key, String name, String shortName) {}

  public record Rule(
      String materialCode,
      String materialName,
      String specification,
      String supplierName,
      String unit,
      String priceType,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          BigDecimal originalPrice,
      String formula,
      CompiledFormula compiled,
      Map<String, String> factors,
      String sheetName,
      int sourceRow,
      Map<String, String> originalFields) {}

  public record Parsed(List<Rule> rows, List<String> sheets) {
    public Parsed {
      rows = List.copyOf(rows);
      sheets = List.copyOf(sheets);
    }
  }

  private record Header(Sheet sheet, int row, Map<String, Integer> columns, int lastColumn) {}

  private record Bindings(
      Map<Address, String> variables, Map<String, String> keys, Map<Address, String> unresolved) {}

  private static final Set<String> REQUIRED =
      Set.of("料号", "规格型号", "供方名称", "价格类型", "批量价总价(元)", "不含税价格");
  private static final Set<String> COLUMNS =
      Set.of(
          "料号",
          "零件名称",
          "规格型号",
          "材料牌号及规格",
          "计量单位",
          "年用量(万)",
          "类似品名称",
          "类似品规格",
          "与类似品异同点说明(包括模具说明)",
          "毛重(克)",
          "净重(克)",
          "批量价加工费(元)",
          "批量价总价(元)",
          "样品价加工费(元)",
          "样品价总价(元)",
          "价格是否含模具费(元)",
          "模具费金额不含税(元)",
          "供方名称",
          "备注及联动公式",
          "价格类型",
          "铜基价",
          "不含税价格");

  public Parsed parse(byte[] bytes, String fileName, List<Factor> catalog) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("请上传非空且不超过10 MB的询价工作簿");
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var headers = headers(book);
      if (headers.isEmpty())
        throw new IllegalArgumentException("未找到询价表头：料号、规格型号、供方名称、价格类型、批量价总价(元)、不含税价格");
      var bindings = bindings(book, headers, catalog);
      var compiler = new WorkbookFormulaCompiler(book, fileName, bindings.variables());
      var rules = new ArrayList<Rule>();
      for (var header : headers) {
        for (int index = header.row() + 1; index <= header.sheet().getLastRowNum(); index++) {
          var row = header.sheet().getRow(index);
          if (row == null || emptyData(row, header)) continue;
          try {
            if (rules.size() >= 20000) throw new IllegalArgumentException("单次导入最多20000条询价");
            String material = field(row, header, "料号", 64);
            String specification = field(row, header, "规格型号", 512);
            if (material.isBlank() && specification.isBlank())
              throw new IllegalArgumentException("料号和规格型号至少填写一项");
            String type = InquiryNames.normalize(field(row, header, "价格类型", 16));
            if (!Set.of("固定价", "联动价").contains(type))
              throw new IllegalArgumentException("价格类型须为固定价或联动价");
            Cell price = cell(row, header, type.equals("固定价") ? "批量价总价(元)" : "不含税价格");
            if (price == null || text(price).isBlank())
              throw new IllegalArgumentException(type + "缺少价格");
            CompiledFormula compiled = null;
            Map<String, String> factors = new LinkedHashMap<>();
            BigDecimal original;
            if (type.equals("联动价")) {
              if (price.getCellType() != CellType.FORMULA)
                throw new IllegalArgumentException("联动价的不含税价格单元格必须保留实际公式");
              compiled =
                  compiler.compile(
                      new Address(
                          header.sheet().getSheetName(), price.getAddress().formatAsString()));
              for (var source : compiled.sources()) {
                String unknown = bindings.unresolved().get(source.address());
                if (unknown != null)
                  throw new IllegalArgumentException("影响因素无法唯一对应财务目录：" + unknown);
              }
              compiled
                  .sourceInputs()
                  .keySet()
                  .forEach(variable -> factors.put(variable, bindings.keys().get(variable)));
              original =
                  ExpressionEvaluator.evaluate(compiled.expression(), compiled.sourceInputs());
              if (original.subtract(decimal(price)).abs().compareTo(new BigDecimal("0.00000001"))
                  > 0) throw new IllegalArgumentException("公式原输入复算与不含税价格缓存不一致，请检查原表公式");
            } else if (price.getCellType() == CellType.FORMULA) {
              var fixed =
                  new WorkbookFormulaCompiler(book, fileName, Map.of())
                      .compile(
                          new Address(
                              header.sheet().getSheetName(), price.getAddress().formatAsString()));
              original = ExpressionEvaluator.evaluate(fixed.expression(), Map.of());
            } else original = decimal(price);
            amount(original);
            var fields = new LinkedHashMap<String, String>();
            for (String label : header.columns().keySet())
              fields.put(label, text(cell(row, header, label)));
            rules.add(
                new Rule(
                    material,
                    field(row, header, "零件名称", 255),
                    specification,
                    field(row, header, "供方名称", 255),
                    field(row, header, "计量单位", 32),
                    type,
                    original,
                    price.getCellType() == CellType.FORMULA ? price.getCellFormula() : null,
                    compiled,
                    Map.copyOf(factors),
                    header.sheet().getSheetName(),
                    index + 1,
                    Map.copyOf(fields)));
          } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                header.sheet().getSheetName() + " 第" + (index + 1) + "行：" + invalid.getMessage(),
                invalid);
          }
        }
      }
      if (rules.isEmpty()) throw new IllegalArgumentException("工作簿没有询价数据");
      return new Parsed(rules, headers.stream().map(h -> h.sheet().getSheetName()).toList());
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("无法读取询价工作簿", invalid);
    }
  }

  private static List<Header> headers(Workbook book) {
    var result = new ArrayList<Header>();
    for (var sheet : book)
      for (int index = 0; index <= Math.min(29, sheet.getLastRowNum()); index++) {
        var row = sheet.getRow(index);
        if (row == null) continue;
        var columns = new LinkedHashMap<String, Integer>();
        for (var cell : row)
          if (cell.getCellType() == CellType.STRING) {
            String label = InquiryNames.normalize(cell.getStringCellValue()).replace("\"", "");
            if (COLUMNS.contains(label)
                && columns.putIfAbsent(label, cell.getColumnIndex()) != null)
              throw new IllegalArgumentException(sheet.getSheetName() + " 表头重复：" + label);
          }
        if (columns.keySet().containsAll(REQUIRED)) {
          result.add(new Header(sheet, index, columns, Collections.max(columns.values())));
          break;
        }
      }
    return result;
  }

  private static Bindings bindings(Workbook book, List<Header> headers, List<Factor> catalog) {
    var variables = new LinkedHashMap<Address, String>();
    var keys = new LinkedHashMap<String, String>();
    var unresolved = new LinkedHashMap<Address, String>();
    var mainSheets = new HashMap<String, Header>();
    headers.forEach(header -> mainSheets.put(header.sheet().getSheetName(), header));
    for (var sheet : book)
      for (var row : sheet)
        for (var label : row) {
          if (label.getCellType() != CellType.STRING) continue;
          String name = InquiryNames.normalize(label.getStringCellValue());
          if (name.isBlank() || name.matches("[+-]?[0-9.]+")) continue;
          Cell value = row.getCell(label.getColumnIndex() + 1);
          if (value == null || text(value).isBlank()) continue;
          if (value.getCellType() != CellType.FORMULA) {
            try {
              decimal(value);
            } catch (IllegalArgumentException notNumeric) {
              continue;
            }
          }
          var address = new Address(sheet.getSheetName(), value.getAddress().formatAsString());
          var matches = factorKeys(name, catalog, true);
          if (matches.isEmpty()) matches = factorKeys(name, catalog, false);
          if (matches.size() == 1) {
            String variable = "factor_" + variables.size();
            variables.put(address, variable);
            keys.put(variable, matches.iterator().next());
          } else {
            var header = mainSheets.get(sheet.getSheetName());
            if (header == null || label.getColumnIndex() > header.lastColumn())
              unresolved.put(address, label.getStringCellValue());
          }
        }
    return new Bindings(variables, keys, unresolved);
  }

  private static Set<String> factorKeys(String name, List<Factor> catalog, boolean exact) {
    var keys = new LinkedHashSet<String>();
    for (var factor : catalog) {
      if (factor.key() == null || factor.key().isBlank()) continue;
      for (String alias :
          List.of(
              Objects.toString(factor.name(), ""),
              Objects.toString(factor.shortName(), ""),
              factor.key())) {
        String normalized = InquiryNames.normalize(alias);
        if (name.equals(normalized) || !exact && name.length() >= 3 && normalized.contains(name))
          keys.add(factor.key());
      }
    }
    return keys;
  }

  private static boolean emptyData(Row row, Header header) {
    return List.of("料号", "规格型号", "价格类型", "批量价总价(元)", "不含税价格").stream()
        .allMatch(label -> text(cell(row, header, label)).isBlank());
  }

  private static Cell cell(Row row, Header header, String label) {
    Integer column = header.columns().get(label);
    return column == null ? null : row.getCell(column);
  }

  private static String field(Row row, Header header, String label, int max) {
    String value = text(cell(row, header, label)).strip();
    if (value.length() > max || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException(label + "过长或含无效字符");
    return value;
  }

  private static String text(Cell cell) {
    if (cell == null) return "";
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    return switch (type) {
      case STRING -> cell.getStringCellValue().strip();
      case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
      case BLANK, _NONE -> "";
      default -> throw new IllegalArgumentException("单元格含错误或布尔值：" + cell.getAddress());
    };
  }

  private static BigDecimal decimal(Cell cell) {
    try {
      return new BigDecimal(text(cell));
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("单元格不是有效数值：" + cell.getAddress());
    }
  }

  static void amount(BigDecimal amount) {
    if (amount == null || amount.signum() < 0 || amount.precision() - amount.scale() > 20)
      throw new IllegalArgumentException("询价结果须为非负价格且最多20位整数");
  }
}
