package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;

/** 只识别废料清单及价格实际依赖，不把映射表或参考基价当成导入数据。 */
@Component
public class ScrapPriceWorkbookParser {
  private static final String SHEET = "废料清单";
  private static final Map<String, String> LABEL_VARIABLE =
      Map.of("1#电解铜价", "Cu", "1#电解铜价格", "Cu", "美国柜装黄铜", "Brass", "长江现货A00", "Al");
  private static final Map<String, String> FACTORS =
      Map.of("Cu", "AVG|1#CU", "Brass", "AVG|美国柜装黄铜", "Al", "AVG|A00");
  private final ObjectMapper json;

  public ScrapPriceWorkbookParser(ObjectMapper json) {
    this.json = json;
  }

  public record SourceRow(int row, ScrapPriceRule rule, String evidenceJson) {}

  public record Problem(int row, String materialCode, String message) {}

  public record Parsed(
      String fileName, String sha256, List<SourceRow> rows, List<Problem> errors, int duplicates) {
    public Parsed {
      rows = List.copyOf(rows);
      errors = List.copyOf(errors);
    }
  }

  public Parsed parse(byte[] bytes, String fileName) {
    if (bytes == null || bytes.length == 0 || bytes.length > 20 * 1024 * 1024)
      throw new IllegalArgumentException("请选择不超过20MB的废料价格Excel");
    fileName = ScrapPriceRule.required(fileName, "文件名", 255);
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Sheet sheet = workbook.getSheet(SHEET);
      if (sheet == null) throw new IllegalArgumentException("文件缺少“废料清单”工作表");
      DataFormatter formatter = new DataFormatter(Locale.ROOT);
      int header = headerRow(sheet, formatter);
      Map<String, Integer> columns = columns(sheet.getRow(header), formatter);
      var factors = factors(sheet, formatter);
      var compiler = new WorkbookFormulaCompiler(workbook, fileName, factors);
      var rows = new LinkedHashMap<MaterialSupplier, SourceRow>();
      var errors = new ArrayList<Problem>();
      int duplicates = 0;
      for (int index = header + 1; index <= sheet.getLastRowNum(); index++) {
        Row row = sheet.getRow(index);
        if (row == null) continue;
        String code = text(row, columns, "回收料号", formatter);
        if (code.isBlank()) {
          if (!text(row, columns, "回收料品名", formatter).isBlank()
              || !text(row, columns, "不含税价", formatter).isBlank())
            errors.add(new Problem(index + 1, "", "废料料号不能为空"));
          continue;
        }
        try {
          Cell price = row.getCell(columns.get("不含税价"));
          String declared = text(row, columns, "价格类型", formatter);
          String type =
              switch (declared) {
                case "固定价", "FIXED" -> "FIXED";
                case "联动价", "LINKED" -> "LINKED";
                default -> throw new IllegalArgumentException("价格类型仅支持固定价/联动价");
              };
          BigDecimal fixed = null;
          String rawFormula = null;
          String expression = null;
          Map<String, String> bindings = new TreeMap<>();
          Object sources = List.of();
          if ("LINKED".equals(type)) {
            if (price == null || price.getCellType() != CellType.FORMULA)
              throw new IllegalArgumentException("联动价必须在不含税价单元格保存实际Excel公式");
            rawFormula = price.getCellFormula();
            var compiled =
                compiler.compile(new Address(SHEET, price.getAddress().formatAsString()));
            expression = compiled.expression();
            if (ExpressionEvaluator.evaluate(expression, compiled.sourceInputs()).signum() < 0)
              throw new IllegalArgumentException("联动公式在原始因素下计算为负价，请核对实际公式");
            for (String variable : ExpressionEvaluator.extractVariables(expression)) {
              String identity = FACTORS.get(variable);
              if (identity == null) throw new IllegalArgumentException("未识别的废料因素：" + variable);
              bindings.put(variable, identity);
            }
            sources = compiled;
          } else {
            if (price == null || price.getCellType() != CellType.NUMERIC)
              throw new IllegalArgumentException("固定价必须填写实际数值");
            fixed = number(price);
          }
          var rule =
              new ScrapPriceRule(
                  code,
                  text(row, columns, "回收料品名", formatter),
                  text(row, columns, "回收料规格", formatter),
                  text(row, columns, "回收料单位", formatter),
                  text(row, columns, "供应商代码", formatter),
                  text(row, columns, "供应商名称", formatter),
                  type,
                  fixed,
                  rawFormula,
                  expression,
                  bindings);
          var evidence = new LinkedHashMap<String, Object>();
          evidence.put("sheet", SHEET);
          evidence.put("row", index + 1);
          evidence.put("priceCell", price.getAddress().formatAsString());
          evidence.put("sourceFormula", rawFormula);
          evidence.put("sourcePrice", number(price));
          evidence.put("formulaDescription", text(row, columns, "联动公式", formatter));
          evidence.put("source", text(row, columns, "来源", formatter));
          evidence.put("approvalType", text(row, columns, "批单类型", formatter));
          evidence.put("approvalNo", text(row, columns, "批单编号", formatter));
          evidence.put("dependencies", sources);
          var parsed = new SourceRow(index + 1, rule, json.writeValueAsString(evidence));
          var key =
              new MaterialSupplier(
                  rule.materialCode(),
                  ScrapPriceIdentity.supplierKey(rule.supplierCode(), rule.supplierName()));
          SourceRow prior = rows.putIfAbsent(key, parsed);
          if (prior != null) {
            if (!prior.rule().contentHash(json).equals(rule.contentHash(json)))
              throw new IllegalArgumentException("与第" + prior.row() + "行身份相同但内容不同");
            duplicates++;
          }
        } catch (IllegalArgumentException | ArithmeticException error) {
          errors.add(new Problem(index + 1, code, error.getMessage()));
        }
      }
      if (rows.isEmpty() && errors.isEmpty()) throw new IllegalArgumentException("废料清单没有价格记录");
      return new Parsed(
          fileName,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          new ArrayList<>(rows.values()),
          errors,
          duplicates);
    } catch (IllegalArgumentException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalArgumentException("废料Excel读取失败：" + failure.getMessage(), failure);
    }
  }

  private static int headerRow(Sheet sheet, DataFormatter formatter) {
    for (int r = sheet.getFirstRowNum(); r <= Math.min(sheet.getLastRowNum(), 20); r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;
      Map<String, Integer> names = columns(row, formatter);
      if (names.keySet().containsAll(List.of("回收料号", "回收料品名", "回收料单位", "不含税价", "价格类型"))) return r;
    }
    throw new IllegalArgumentException("废料清单缺少料号、名称、单位、不含税价或价格类型表头");
  }

  private static Map<String, Integer> columns(Row header, DataFormatter formatter) {
    var columns = new LinkedHashMap<String, Integer>();
    for (Cell cell : header) {
      String value = formatter.formatCellValue(cell).trim();
      if (!value.isEmpty()) columns.putIfAbsent(value, cell.getColumnIndex());
    }
    return columns;
  }

  private static Map<Address, String> factors(Sheet sheet, DataFormatter formatter) {
    var result = new LinkedHashMap<Address, String>();
    for (Row row : sheet)
      for (Cell cell : row) {
        if (cell.getCellType() != CellType.STRING) continue;
        String variable = LABEL_VARIABLE.get(formatter.formatCellValue(cell).trim());
        Cell value = row.getCell(cell.getColumnIndex() + 1);
        if (variable != null
            && value != null
            && (value.getCellType() == CellType.NUMERIC || value.getCellType() == CellType.FORMULA))
          result.put(new Address(SHEET, value.getAddress().formatAsString()), variable);
      }
    return result;
  }

  private static String text(
      Row row, Map<String, Integer> columns, String name, DataFormatter formatter) {
    Integer column = columns.get(name);
    return column == null ? "" : formatter.formatCellValue(row.getCell(column)).trim();
  }

  private static BigDecimal number(Cell cell) {
    if (cell.getCellType() == CellType.FORMULA
        && cell.getCachedFormulaResultType() != CellType.NUMERIC)
      return null; // 缓存仅为来源证据；实际公式仍由编译器校验。
    if (cell instanceof XSSFCell x)
      return x.getRawValue() == null || x.getRawValue().isBlank()
          ? null
          : new BigDecimal(x.getRawValue());
    return BigDecimal.valueOf(cell.getNumericCellValue());
  }

  private record MaterialSupplier(String material, String supplier) {}
}
