package com.sanhua.marketingcost.service.impl;

import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.numeric;
import static com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.text;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Band;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Bounds;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.CellSource;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.RowError;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Rule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** 网页补档另记在原 Excel 内，原矩阵位置、公式引用及档差说明保持。 */
final class OriginalRangeBandWorkbook {
  static final String SHEET = "系统补充行情档位";
  private static final List<String> HEADERS = List.of(
      "原稿主表", "原稿行号", "料号", "供应商", "供应商代码", "规格", "行情单位",
      "区间下限", "区间上限", "包含下限", "包含上限", "单价", "税口径");
  private static final BigDecimal VALUE_LIMIT = new BigDecimal("1000000000000");
  private static final BigDecimal VAT = new BigDecimal("1.13");
  private static final BigDecimal CACHE_TOLERANCE = new BigDecimal("0.00000001");

  private OriginalRangeBandWorkbook() {}

  static byte[] append(byte[] bytes, Rule rule, Bounds bounds, BigDecimal price) {
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes));
        var out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.getSheet(SHEET);
      if (sheet == null) {
        sheet = workbook.createSheet(SHEET);
        writeRow(sheet.createRow(0), HEADERS);
      } else if (!validHeader(sheet)) {
        throw new IllegalArgumentException("原稿存在同名工作表「" + SHEET + "」");
      }
      writeRow(sheet.createRow(sheet.getLastRowNum() + 1), List.of(
          rule.sheet(), Integer.toString(rule.row()), rule.identity().materialCode(),
          rule.identity().supplierName(), rule.identity().supplierCode(), rule.identity().specification(),
          rule.factorUnit().name(), bounds.low().toPlainString(),
          bounds.high().toPlainString(), flag(bounds.lowerInclusive()), flag(bounds.upperInclusive()),
          checkedPrice(price, rule.taxIncluded()).toPlainString(), tax(rule)));
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException error) {
      throw new IllegalArgumentException("无法在原稿中保存补充档位", error);
    }
  }

  static void read(Workbook book, WorkbookFormulaCompiler compiler, List<Rule> rules, List<RowError> errors) {
    Sheet sheet = book.getSheet(SHEET);
    if (sheet == null) return;
    if (!validHeader(sheet)) {
      errors.add(new RowError(null, null, null, "工作表「" + SHEET + "」的表头不完整"));
      return;
    }
    Map<SourceRow, Integer> sourceIndexes = new HashMap<>();
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      sourceIndexes.put(new SourceRow(rule.sheet(), rule.row()), i);
    }
    for (int i = 1; i <= sheet.getLastRowNum(); i++) {
      Row row = sheet.getRow(i);
      if (row == null || isEmpty(row)) continue;
      Rule rule = null;
      String material = text(row.getCell(2));
      try {
        int sourceRow = decimal(row.getCell(1), "原稿行号").intValueExact();
        Integer position = sourceIndexes.get(new SourceRow(text(row.getCell(0)), sourceRow));
        if (position == null) throw new IllegalArgumentException("未找到补充档位对应的原稿行");
        rule = rules.get(position);
        if (!List.of(rule.identity().materialCode(), rule.identity().supplierName(),
                rule.identity().supplierCode(), rule.identity().specification(), rule.factorUnit().name(),
                tax(rule)).equals(List.of(material, text(row.getCell(3)), text(row.getCell(4)),
                text(row.getCell(5)), text(row.getCell(6)), text(row.getCell(12)))))
          throw new IllegalArgumentException("补充档位与原稿的料号、供应商、规格、行情单位或税口径不一致");
        Bounds bounds = bounds(decimal(row.getCell(7), "区间下限"),
            decimal(row.getCell(8), "区间上限"), included(row.getCell(9)), included(row.getCell(10)));
        rules.set(position, addBand(rule, row, bounds, compiler));
      } catch (RuntimeException error) {
        errors.add(new RowError(rule == null ? null : rule.sheet(), rule == null ? null : rule.row(),
            material, SHEET + "第" + (i + 1) + "行：" + error.getMessage()));
      }
    }
  }

  static Bounds bounds(BigDecimal low, BigDecimal high, boolean includeLow, boolean includeHigh) {
    return new Bounds(validateFactor(low), validateFactor(high), includeLow, includeHigh);
  }

  private static Rule addBand(Rule rule, Row row, Bounds bounds, WorkbookFormulaCompiler compiler) {
    var bands = new ArrayList<>(rule.bands());
    var key = OriginalRangeSupplement.normalize(bounds);
    int replace = -1;
    for (int i = 0; i < bands.size(); i++) {
      Band band = bands.get(i);
      if (key.equals(OriginalRangeSupplement.normalize(band.bounds())) && OriginalRangeSupplement.blank(band)
          && replace < 0) {
        replace = i;
      } else if (OriginalRangeSupplement.overlaps(band.bounds(), bounds)) {
        throw new IllegalArgumentException("新增档位与已有档位重叠：" + band.header().rawValue());
      }
    }
    Cell cell = row.getCell(11);
    var source = OriginalRangeWorkbookParser.source(cell);
    BigDecimal price = checkedPrice(decimal(cell, "单价"), rule.taxIncluded());
    CompiledFormula compiled;
    if (cell.getCellType() == CellType.FORMULA) {
      compiled = compiler.compile(source.address());
      BigDecimal value = ExpressionEvaluator.evaluate(compiled.expression(), Map.of());
      if (value.subtract(price).abs().compareTo(CACHE_TOLERANCE) > 0)
        throw new IllegalArgumentException("补充单价公式与 Excel 保存值不一致");
      price = checkedPrice(value, rule.taxIncluded());
    } else {
      compiled = new CompiledFormula(price.toPlainString(), List.of(), Map.of());
    }
    String interval = (bounds.lowerInclusive() ? "[" : "(") + bounds.low().toPlainString() + ", "
        + bounds.high().toPlainString() + (bounds.upperInclusive() ? "]" : ")");
    var headerCell = OriginalRangeWorkbookParser.source(row.getCell(7));
    var header = new CellSource(headerCell.address(), interval, "STRING", null, null);
    var added = new Band(bounds, header, source.address(), source, compiled, price, null);
    if (replace < 0) bands.add(added);
    else bands.set(replace, added);
    return OriginalRangeSupplement.withBands(rule, bands);
  }

  private static BigDecimal validateFactor(BigDecimal value) {
    if (value == null || value.signum() < 0 || value.compareTo(VALUE_LIMIT) >= 0
        || value.stripTrailingZeros().scale() > 6)
      throw new IllegalArgumentException("区间上下限须为非负数，最多 6 位小数");
    return value.stripTrailingZeros();
  }

  private static BigDecimal checkedPrice(BigDecimal value, boolean taxIncluded) {
    if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 36
        || (taxIncluded ? value : value.multiply(VAT)).compareTo(VALUE_LIMIT) >= 0)
      throw new IllegalArgumentException("单价超出可用范围");
    return value.stripTrailingZeros();
  }

  private static BigDecimal decimal(Cell cell, String field) {
    var value = numeric(cell);
    if (value == null) throw new IllegalArgumentException(field + "必须填写数字");
    return value;
  }

  private static boolean included(Cell cell) {
    return switch (text(cell)) {
      case "是" -> true;
      case "否" -> false;
      default -> throw new IllegalArgumentException("边界是否包含请填「是」或「否」");
    };
  }

  private static String flag(boolean included) {
    return included ? "是" : "否";
  }

  private static String tax(Rule rule) {
    return rule.taxIncluded() ? "含税" : "未税";
  }

  private static boolean validHeader(Sheet sheet) {
    Row row = sheet.getRow(0);
    if (row == null) return false;
    for (int i = 0; i < HEADERS.size(); i++)
      if (!HEADERS.get(i).equals(text(row.getCell(i)))) return false;
    return true;
  }

  private static boolean isEmpty(Row row) {
    for (Cell cell : row)
      if (!text(cell).isBlank()) return false;
    return true;
  }

  private static void writeRow(Row row, List<String> values) {
    for (int i = 0; i < values.size(); i++) row.createCell(i).setCellValue(values.get(i));
  }

  private record SourceRow(String sheet, int row) {}
}
