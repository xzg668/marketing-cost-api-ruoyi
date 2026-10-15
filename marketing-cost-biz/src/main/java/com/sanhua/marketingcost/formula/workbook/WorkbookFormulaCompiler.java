package com.sanhua.marketingcost.formula.workbook;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;

/** 沿最终价格格读取实际依赖；只有调用方明确识别的因素格转换为动态变量。 */
public final class WorkbookFormulaCompiler {
  private final Workbook workbook;
  private final String fileName;
  private final Map<String, String> variableByCell;

  public WorkbookFormulaCompiler(Workbook workbook, String fileName, Map<Address, String> factors) {
    this.workbook = workbook;
    this.fileName = fileName;
    Map<String, String> variables = new LinkedHashMap<>();
    factors.forEach(
        (address, variable) -> {
          if (variable == null || !variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("因素变量名非法：" + variable);
          }
          variables.put(ExcelCellReferences.key(address.sheet(), address.cell()), variable);
        });
    variableByCell = Map.copyOf(variables);
  }

  public CompiledFormula compile(Address priceCell) {
    Compilation compilation = new Compilation();
    String expression = compilation.cell(priceCell);
    try {
      ExpressionEvaluator.validate(expression);
    } catch (IllegalArgumentException error) {
      throw problem(priceCell, error.getMessage());
    }
    return new CompiledFormula(
        expression,
        List.copyOf(compilation.sources.values()),
        Collections.unmodifiableMap(new LinkedHashMap<>(compilation.sourceInputs)));
  }

  public record Address(String sheet, String cell) {
    public Address {
      if (sheet == null || sheet.isBlank() || cell == null || cell.isBlank()) {
        throw new IllegalArgumentException("源Sheet和单元格必填");
      }
      CellReference reference = new CellReference(cell.replace("$", ""));
      cell = new CellReference(reference.getRow(), reference.getCol()).formatAsString();
    }
  }

  public record SourceCell(
      Address address,
      String formula,
      String rawValue,
      String cellType,
      String numberFormat,
      String variable) {}

  public record CompiledFormula(
      String expression, List<SourceCell> sources, Map<String, BigDecimal> sourceInputs) {}

  private final class Compilation {
    private final Map<Address, String> expressions = new LinkedHashMap<>();
    private final Map<Address, SourceCell> sources = new LinkedHashMap<>();
    private final Map<String, BigDecimal> sourceInputs = new LinkedHashMap<>();
    private final LinkedHashSet<Address> visiting = new LinkedHashSet<>();

    private String cell(Address address) {
      if (expressions.containsKey(address)) return expressions.get(address);
      if (!visiting.add(address)) throw problem(address, "循环引用：" + visiting);
      if (visiting.size() > 128) throw problem(address, "依赖超过128层");
      var sheet = workbook.getSheet(address.sheet());
      if (sheet == null) throw problem(address, "引用的Sheet不存在");
      var reference = new CellReference(address.cell());
      var row = sheet.getRow(reference.getRow());
      Cell cell = row == null ? null : row.getCell(reference.getCol());
      String variable =
          variableByCell.get(ExcelCellReferences.key(address.sheet(), address.cell()));
      String formula =
          cell != null && cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null;
      String raw = rawValue(cell);
      sources.put(
          address,
          new SourceCell(
              address,
              formula,
              raw,
              cell == null ? "BLANK" : cell.getCellType().name(),
              cell == null ? null : cell.getCellStyle().getDataFormatString(),
              variable));
      String result;
      if (variable != null) {
        if (raw == null || raw.isBlank()) throw problem(address, "因素原输入缺失：" + variable);
        BigDecimal original = decimal(address, raw);
        BigDecimal previous = sourceInputs.putIfAbsent(variable, original);
        if (previous != null && previous.compareTo(original) != 0) {
          throw problem(address, "同一因素的原输入冲突：" + variable);
        }
        result = "[" + variable + "]";
      } else if (formula != null) {
        result = formula(address, formula);
      } else {
        // Excel普通空白引用按0参与四则计算，并在来源中明确记录为空白；因素空白在上方阻断。
        result = raw == null || raw.isBlank() ? "0" : decimal(address, raw).toPlainString();
      }
      visiting.remove(address);
      expressions.put(address, result);
      return result;
    }

    private String formula(Address address, String formula) {
      if (formula.contains("[") || formula.contains("]")) throw problem(address, "不支持外部工作簿或结构化引用");
      if (formula.contains(":")) throw problem(address, "本批原稿不支持区域引用");
      StringBuilder result = new StringBuilder();
      int cursor = 0;
      for (var reference : ExcelCellReferences.parse(formula, address.sheet())) {
        result.append(formula, cursor, reference.start());
        result
            .append('(')
            .append(cell(new Address(reference.sheetName(), reference.cellRef())))
            .append(')');
        if (result.length() > 1_000_000) throw problem(address, "展开公式超过支持长度");
        cursor = reference.end();
      }
      result.append(formula.substring(cursor));
      String expression = result.toString();
      try {
        // 尚有名称引用或未知函数时拒绝导入，不能退回说明文字或缓存价。
        for (String variable : ExpressionEvaluator.extractVariables(expression)) {
          if (!variableByCell.containsValue(variable)) throw problem(address, "未解析的名称：" + variable);
        }
      } catch (IllegalArgumentException error) {
        throw problem(address, error.getMessage());
      }
      return expression;
    }
  }

  private BigDecimal decimal(Address address, String value) {
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException error) {
      throw problem(address, "引用值不是数字：" + value);
    }
  }

  private String rawValue(Cell cell) {
    if (cell == null) return null;
    CellType type =
        cell.getCellType() == CellType.FORMULA
            ? cell.getCachedFormulaResultType()
            : cell.getCellType();
    return switch (type) {
      case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
      case STRING -> cell.getStringCellValue();
      case BOOLEAN -> cell.getBooleanCellValue() ? "1" : "0";
      case BLANK, _NONE -> null;
      case ERROR ->
          org.apache.poi.ss.usermodel.FormulaError.forInt(cell.getErrorCellValue()).getString();
      default -> throw new IllegalArgumentException("无法读取单元格值：" + cell.getAddress());
    };
  }

  private IllegalArgumentException problem(Address address, String message) {
    return new IllegalArgumentException(
        fileName + " / " + address.sheet() + "!" + address.cell() + "：" + message);
  }
}
