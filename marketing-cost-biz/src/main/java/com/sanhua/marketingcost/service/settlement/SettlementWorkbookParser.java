package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementWeights;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;

/** 只解释原件，不查询数据库、不推算缺失重量，也不改变原稿的给定价格。 */
@Component
public class SettlementWorkbookParser {
  private static final Pattern FORMULA_TAIL = Pattern.compile("/1000/([0-9]+(?:\\.[0-9]+)?)\\)?\\*([0-9]+(?:\\.[0-9]+)?)$");

  public record CellValue(String text, BigDecimal number, String formula) {}
  public record SourceRow(int row, Map<String, CellValue> cells, String formulaSourceCell, String resolvedSourceFormula) {
    public SourceRow { cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells)); }
  }
  public record FormulaInputs(BigDecimal plannedPrice, BigDecimal markupRatio, BigDecimal baseSettlePrice,
      BigDecimal budgetCu, BigDecimal budgetZn, BigDecimal referenceCu, BigDecimal referenceZn,
      BigDecimal taxDivisor, String expression, String version) {}
  public record Rule(String materialCode, String materialName, String model, SettlementPriceType priceType,
      BigDecimal givenPrice, FormulaInputs formula, SettlementWeights weights, List<SourceRow> sources, String remark) {
    public Rule { sources = List.copyOf(sources); }
  }
  public record ParsedWorkbook(String fileName, String sha256, String sheet, List<Rule> rules,
      List<SourceRow> sourceRows, BigDecimal referenceCu, BigDecimal referenceZn) {
    public ParsedWorkbook { rules = List.copyOf(rules); sourceRows = List.copyOf(sourceRows); }
  }
  private record Header(Sheet sheet, int row, int code, int name, int model, int planned, int markup,
      int base, int copper, int zinc, int given, int remark, int type, int formula) {}
  private record Factor(int row, int column, BigDecimal budget, BigDecimal reference) {}
  private record FormulaSource(BigDecimal taxDivisor, String cell, String expression) {}

  public ParsedWorkbook parse(byte[] bytes, String fileName) {
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException(fileName + "：工作簿为空");
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Header header = findHeader(workbook, fileName);
      Factor cu = factor(header.sheet(), "铜", fileName);
      Factor zn = factor(header.sheet(), "锌", fileName);
      List<Row> data = new ArrayList<>();
      for (int index = header.row() + 1; index <= header.sheet().getLastRowNum(); index++) {
        Row row = header.sheet().getRow(index);
        if (row == null) continue;
        if (!text(row.getCell(header.code())).isBlank() || !text(row.getCell(header.type())).isBlank()
            || !text(row.getCell(header.given())).isBlank()) data.add(row);
      }
      FormulaSource sharedFormula = null;
      for (Row row : data) {
        String raw = formulaText(row.getCell(header.formula()));
        if (linked(text(row.getCell(header.type()))) && !raw.isBlank() && !sameFormula(raw)) {
          sharedFormula = new FormulaSource(taxDivisor(row, header, cu, zn, fileName),
              address(row.getRowNum(), header.formula()), raw);
          break;
        }
      }
      List<SourceRow> sources = new ArrayList<>();
      Map<String, Rule> rules = new LinkedHashMap<>();
      for (Row row : data) {
        var rule = rule(row, header, cu, zn, sharedFormula, fileName);
        sources.addAll(rule.sources());
        String key = rule.materialCode() + ":" + rule.priceType();
        Rule previous = rules.get(key);
        if (previous == null) rules.put(key, rule);
        else {
          if (!sameBusiness(previous, rule)) throw error(fileName, header.sheet(), row.getRowNum(), header.code(),
              "同料号同价格类型与第 " + previous.sources().getFirst().row() + " 行冲突");
          List<SourceRow> merged = new ArrayList<>(previous.sources());
          merged.addAll(rule.sources());
          rules.put(key, new Rule(previous.materialCode(), previous.materialName(), previous.model(), previous.priceType(),
              previous.givenPrice(), previous.formula(), previous.weights(), merged, join(previous.remark(), rule.remark())));
        }
      }
      if (rules.isEmpty()) throw new IllegalArgumentException(fileName + "：没有结算价格明细");
      return new ParsedWorkbook(fileName, sha256(bytes), header.sheet().getSheetName(), List.copyOf(rules.values()),
          sources, cu.reference(), zn.reference());
    } catch (IllegalArgumentException invalid) { throw invalid; }
    catch (Exception invalid) { throw new IllegalArgumentException(fileName + "：无法读取结算工作簿：" + invalid.getMessage(), invalid); }
  }

  private Rule rule(Row row, Header h, Factor cu, Factor zn, FormulaSource sharedFormula, String file) {
    String code = required(row, h.code(), h, file, "料号");
    String name = required(row, h.name(), h, file, "料品名称");
    String kind = required(row, h.type(), h, file, "价格类型");
    SettlementPriceType type = switch (kind) {
      case "固定价格", "固定价", "结算固定价", "SETTLE_FIXED" -> SettlementPriceType.SETTLE_FIXED;
      case "联动价格", "联动价", "结算联动价", "SETTLE_LINKED" -> SettlementPriceType.SETTLE_LINKED;
      default -> throw error(file, h.sheet(), row.getRowNum(), h.type(), "未知价格类型：" + kind);
    };
    BigDecimal given = numeric(row, h.given(), h, file, true);
    BigDecimal planned = numeric(row, h.planned(), h, file, true);
    BigDecimal markup = numeric(row, h.markup(), h, file, true);
    if (markup.signum() <= 0) throw error(file, h.sheet(), row.getRowNum(), h.markup(), "上浮倍率必须大于 0");
    BigDecimal base = numeric(row, h.base(), h, file, true);
    String baseFormula = row.getCell(h.base()).getCellType() == CellType.FORMULA ? row.getCell(h.base()).getCellFormula() : "";
    if (!baseFormula.isBlank() && !normalize(baseFormula).equals(address(row.getRowNum(), h.planned()) + "*" + address(row.getRowNum(), h.markup())))
      throw error(file, h.sheet(), row.getRowNum(), h.base(), "基准结算公式应为本行计划价乘上浮倍率");
    BigDecimal tax = null;
    FormulaSource formulaSource = null;
    if (type == SettlementPriceType.SETTLE_LINKED) {
      String raw = formulaText(row.getCell(h.formula()));
      if (sameFormula(raw)) {
        if (sharedFormula == null) throw error(file, h.sheet(), row.getRowNum(), h.formula(), "联动公式相同，但工作簿没有完整公式来源");
        formulaSource = sharedFormula;
      } else formulaSource = new FormulaSource(taxDivisor(row, h, cu, zn, file), address(row.getRowNum(), h.formula()), raw);
      tax = formulaSource.taxDivisor();
    }
    var formula = new FormulaInputs(planned, markup, base, cu.budget(), zn.budget(), cu.reference(), zn.reference(), tax,
        type == SettlementPriceType.SETTLE_LINKED ? SettlementFormula.EXPRESSION : null,
        type == SettlementPriceType.SETTLE_LINKED ? SettlementFormula.VERSION : null);
    var weights = new SettlementWeights(numeric(row, h.copper(), h, file, false), numeric(row, h.zinc(), h, file, false),
        null, null, false, null);
    Map<String, CellValue> cells = new LinkedHashMap<>();
    for (int column = 0; column <= h.formula() + 1; column++) {
      Cell cell = row.getCell(column);
      if (cell != null) cells.put(CellReference.convertNumToColString(column), cellValue(cell, file));
    }
    String remark = join(text(row.getCell(h.remark())), text(row.getCell(h.formula() + 1)));
    return new Rule(code, name, text(row.getCell(h.model())), type, given, formula, weights,
        List.of(new SourceRow(row.getRowNum() + 1, cells, formulaSource == null ? null : formulaSource.cell(),
            formulaSource == null ? null : formulaSource.expression())), remark);
  }

  private BigDecimal taxDivisor(Row row, Header h, Factor cu, Factor zn, String file) {
    String actual = normalize(formulaText(row.getCell(h.formula())));
    var tail = FORMULA_TAIL.matcher(actual);
    if (!tail.find()) throw error(file, h.sheet(), row.getRowNum(), h.formula(), "缺失或不支持的结算铜锌公式：" + actual);
    BigDecimal tax = new BigDecimal(tail.group(1));
    BigDecimal markup = numeric(row, h.markup(), h, file, true);
    String difference = "((" + address(cu.row(), cu.column() + 2) + "-"
        + address(cu.row(), cu.column() + 1) + ")*" + address(row.getRowNum(), h.copper()) + "+("
        + address(zn.row(), zn.column() + 2) + "-" + address(zn.row(), zn.column() + 1) + ")*"
        + address(row.getRowNum(), h.zinc()) + ")/1000/" + tail.group(1);
    String base = address(row.getRowNum(), h.base()) + "+";
    // BIFF 的显式括号在 POI 中保留；两种结构数学等价，其他运算分组仍须拒绝。
    Set<String> accepted = Set.of(base + difference + "*" + tail.group(2), base + "(" + difference + ")*" + tail.group(2));
    if (!accepted.contains(actual) || tax.signum() <= 0 || new BigDecimal(tail.group(2)).compareTo(markup) != 0)
      throw error(file, h.sheet(), row.getRowNum(), h.formula(), "公式必须引用本行计划/重量及本表铜锌基价，倍率需与本行一致");
    return tax;
  }

  private Header findHeader(Workbook book, String file) {
    List<Header> matches = new ArrayList<>();
    for (Sheet sheet : book) for (Row row : sheet) {
      int code = column(row, "料号"), type = column(row, "价格类型");
      if (code < 0 || type < 0) continue;
      matches.add(new Header(sheet, row.getRowNum(), code, columnRequired(row, "料品名称", file), columnRequired(row, "型号", file),
          columnRequired(row, "计划价", file), columnRequired(row, "上浮", file), columnRequired(row, "基准结算价", file),
          columnRequired(row, "铜重", file), columnRequired(row, "锌重", file), columnRequired(row, "联动结算价", file),
          columnRequired(row, "备注", file), type, columnRequired(row, "联动公式", file)));
    }
    if (matches.size() != 1) throw new IllegalArgumentException(file + "：应有一张包含料号、价格类型的结算明细表，实际 " + matches.size() + " 张");
    return matches.getFirst();
  }

  private Factor factor(Sheet sheet, String metal, String file) {
    for (Row row : sheet) for (Cell cell : row) {
      String label = text(cell).replaceAll("\\s", "");
      if (label.contains(metal) && label.contains("元/公斤")) {
        for (int offset = 1; offset <= 2; offset++) {
          if (row.getCell(cell.getColumnIndex() + offset) == null)
            throw error(file, sheet, row.getRowNum(), cell.getColumnIndex() + offset, "参考基价数值必填");
        }
        BigDecimal budget = number(row.getCell(cell.getColumnIndex() + 1), file, true);
        BigDecimal reference = number(row.getCell(cell.getColumnIndex() + 2), file, true);
        return new Factor(row.getRowNum(), cell.getColumnIndex(), budget, reference);
      }
    }
    throw new IllegalArgumentException(file + " / " + sheet.getSheetName() + "：缺少" + metal + "预算价和参考价（元/公斤）");
  }

  private int column(Row row, String fragment) {
    for (Cell cell : row) if (text(cell).replaceAll("\\s", "").contains(fragment)) return cell.getColumnIndex();
    return -1;
  }
  private int columnRequired(Row row, String label, String file) {
    int result = column(row, label);
    if (result < 0) throw error(file, row.getSheet(), row.getRowNum(), 0, "缺少表头：" + label);
    return result;
  }
  private String required(Row row, int column, Header h, String file, String label) {
    String result = text(row.getCell(column));
    if (result.isBlank()) throw error(file, h.sheet(), row.getRowNum(), column, label + "必填");
    return result;
  }
  private BigDecimal numeric(Row row, int column, Header h, String file, boolean required) {
    Cell cell = row.getCell(column);
    if (cell == null && required) throw error(file, h.sheet(), row.getRowNum(), column, "数值必填");
    return number(cell, file, required);
  }
  private BigDecimal number(Cell cell, String file, boolean required) {
    if (cell == null) {
      if (required) throw new IllegalArgumentException(file + "：参考基价数值必填");
      return null;
    }
    String value = text(cell);
    if (value.isBlank() && !required) return null;
    try {
      BigDecimal number = new BigDecimal(value);
      if (number.signum() < 0) throw new NumberFormatException("negative");
      return number.stripTrailingZeros();
    } catch (NumberFormatException invalid) {
      throw error(file, cell.getSheet(), cell.getRowIndex(), cell.getColumnIndex(), "必须是非负数，空值不作为零");
    }
  }
  private CellValue cellValue(Cell cell, String file) {
    CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    if (type == CellType.ERROR) throw error(file, cell.getSheet(), cell.getRowIndex(), cell.getColumnIndex(), "单元格公式错误");
    return new CellValue(type == CellType.STRING ? cell.getStringCellValue() : text(cell),
        type == CellType.NUMERIC ? BigDecimal.valueOf(cell.getNumericCellValue()) : null,
        cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null);
  }
  private String formulaText(Cell cell) {
    return cell != null && cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : text(cell);
  }
  private String text(Cell cell) {
    if (cell == null) return "";
    CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    return switch (type) {
      case STRING -> cell.getStringCellValue().trim();
      case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
      case BLANK, _NONE -> "";
      case ERROR -> "#" + FormulaError.forInt(cell.getErrorCellValue()).getString();
      default -> cell.toString().trim();
    };
  }
  private boolean linked(String text) { return List.of("联动价格", "联动价", "结算联动价", "SETTLE_LINKED").contains(text); }
  private boolean sameFormula(String text) { return text.replaceAll("\\s", "").equals("联动公式相同"); }
  private String normalize(String formula) { return formula.replace("$", "").replaceAll("\\s", "").replaceFirst("^=", "").replaceAll("(?<=\\d)\\.0+(?![\\d.])", ""); }
  private static String address(int row, int column) { return new CellReference(row, column).formatAsString(); }
  private IllegalArgumentException error(String file, Sheet sheet, int row, int column, String reason) {
    return new IllegalArgumentException(file + " / " + sheet.getSheetName() + " / " + address(row, column) + "（第 " + (row + 1) + " 行）：" + reason);
  }
  private static String join(String first, String second) {
    if (first == null || first.isBlank()) return second == null ? "" : second;
    if (second == null || second.isBlank() || first.equals(second)) return first;
    return first + "\n" + second;
  }
  private boolean sameBusiness(Rule a, Rule b) {
    return a.materialName().equals(b.materialName()) && a.model().equals(b.model())
        && a.givenPrice().compareTo(b.givenPrice()) == 0 && a.formula().equals(b.formula()) && a.weights().equals(b.weights());
  }
  public static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
