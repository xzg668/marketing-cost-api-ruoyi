package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.ExcelCellReferences;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.service.impl.OriginalRangeRuleCalculator.Extrapolation;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellReference;

/** 从保存的原公式和本材料说明核实续算，不读取工作簿、不修改已导入的档位。 */
final class OriginalRangeRoundingProof {
  private static final BigDecimal VAT = new BigDecimal("1.13");
  private static final BigDecimal EPS = new BigDecimal("0.000000000001");
  private static final Pattern ROUND = Pattern.compile("^ROUND\\((.+),(-?\\d+)\\)$");
  private static final Pattern FACTOR = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)元/(吨|T|公斤|KG|克|G)", Pattern.CASE_INSENSITIVE);
  private static final Pattern AMOUNT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)元/(米|只|件|个|套)");

  private OriginalRangeRoundingProof() {}

  static boolean isStepNote(String value) {
    return value != null && (value.contains("上涨") || value.contains("下降") || value.contains("下浮")
        || value.contains("上调") || value.contains("下调") || value.contains("变化") || value.contains("变动"));
  }

  static boolean hasRounding(Band band) {
    return band.compiled() != null && expression(band).contains("ROUND");
  }

  static BigDecimal beforeRounding(Band band) {
    if (band.compiled() == null || band.price() == null || band.problem() != null) return null;
    String expression = expression(band);
    var round = ROUND.matcher(expression);
    String raw = round.matches() ? round.group(1) : expression;
    // 只支持四则运算外的最终 ROUND；嵌套舍入/条件分支不能当作线性序列猜测。
    if (!raw.matches("[0-9.eE+*/()\\-]+")) return null;
    try {
      var value = ExpressionEvaluator.evaluate(raw, Map.of());
      if (!near(ExpressionEvaluator.evaluate(expression, Map.of()), band.price())) return null;
      return value;
    } catch (IllegalArgumentException error) { return null; }
  }

  static int scale(Band band) {
    var round = ROUND.matcher(expression(band));
    if (!round.matches()) throw new IllegalArgumentException("区间末档舍入公式不支持续算");
    return Integer.parseInt(round.group(2));
  }

  private static String expression(Band band) {
    return band.compiled().expression().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  private static List<CellSource> notes(Rule rule) {
    if (rule.extrapolationSources() == null) return List.of();
    int firstPriceColumn = rule.bands().stream().mapToInt(b -> new CellReference(b.address().cell()).getCol()).min().orElse(0);
    return rule.extrapolationSources().stream().filter(s -> isStepNote(s.rawValue()))
        .filter(s -> s.address().sheet().equals(rule.matrixSheet()))
        // 行尾说明仅属于本材料；表左侧的公共说明可供本表材料共同使用。
        .filter(s -> new CellReference(s.address().cell()).getRow()+1 == rule.matrixRow()
            || new CellReference(s.address().cell()).getCol() < firstPriceColumn).toList();
  }

  private static List<BigDecimal> factorSteps(String note, Rule rule) {
    var result = new ArrayList<BigDecimal>(); var match = FACTOR.matcher(note.replaceAll("\\s+", ""));
    while (match.find()) {
      String unit = match.group(2).toUpperCase(Locale.ROOT);
      var source = unit.equals("吨") || unit.equals("T") ? FactorPriceUnit.CNY_TON
          : unit.equals("公斤") || unit.equals("KG") ? FactorPriceUnit.CNY_KG : FactorPriceUnit.CNY_G;
      result.add(source.convert(new BigDecimal(match.group(1)), rule.factorUnit()));
    }
    return result;
  }

  private static List<BigDecimal> amounts(String note) {
    var values = new ArrayList<BigDecimal>(); var match = AMOUNT.matcher(note.replaceAll("\\s+", ""));
    while (match.find()) values.add(new BigDecimal(match.group(1)));
    return values;
  }

  private static boolean deltaMatches(Rule rule, Extrapolation proof, BigDecimal value, String note) {
    if (note != null && note.contains("含税") && !note.contains("不含税") && !note.contains("未税"))
      return near(value, rule.taxIncluded() ? proof.priceStep() : proof.priceStep().multiply(VAT));
    if (note != null && (note.contains("不含税") || note.contains("未税")))
      return near(value, rule.taxIncluded() ? proof.priceStep().divide(VAT, java.math.MathContext.DECIMAL128) : proof.priceStep());
    return near(value, proof.priceStep()) || !rule.taxIncluded() && near(value, proof.priceStep().multiply(VAT));
  }

  static boolean contradictingNote(Rule rule, Extrapolation proof) {
    for (var source : notes(rule)) {
      var factors = factorSteps(source.rawValue(), rule);
      if (!factors.isEmpty() && factors.stream().noneMatch(s -> near(s, proof.factorStep()))) return true;
      var amounts = amounts(source.rawValue());
      if (!amounts.isEmpty() && amounts.stream().noneMatch(s -> deltaMatches(rule, proof, s, source.rawValue()))) return true;
    }
    return false;
  }

  static boolean sourceConfirms(Rule rule, Extrapolation proof) {
    if (contradictingNote(rule, proof)) return false;
    boolean step = false, amount = false;
    for (var source : notes(rule)) {
      if (factorSteps(source.rawValue(), rule).stream().anyMatch(s -> near(s, proof.factorStep()))) {
        step = true;
        amount |= amounts(source.rawValue()).stream().anyMatch(s -> deltaMatches(rule, proof, s, source.rawValue()));
      }
    }
    if (!step) return false;
    if (amount) return true;
    if (rule.extrapolationSources() == null) return false;
    return rule.extrapolationSources().stream()
        .filter(s -> s.address().sheet().equals(rule.matrixSheet())
            && new CellReference(s.address().cell()).getRow()+1 == rule.matrixRow())
        .anyMatch(s -> {
          try {return deltaMatches(rule, proof, new BigDecimal(s.rawValue()), null);}
          catch (NumberFormatException error) {return false;}
        });
  }

  /** 末档明确引用“上一档原价 + 本行档差”时，也可证明尾段的延续，无需借用其他行说明。 */
  static boolean lastFormulaConfirms(Rule rule, Extrapolation proof) {
    var last = proof.anchor(); var previous = proof.evidence().get(proof.evidence().size()-2);
    if (last.compiled() == null || previous.compiled() == null) return false;
    for (var source : last.compiled().sources()) {
      if (source.formula() == null) continue;
      var refs = ExcelCellReferences.parse(source.formula(), source.address().sheet());
      if (refs.size() != 2) continue;
      StringBuilder shape = new StringBuilder(); int end = 0;
      var cells = new ArrayList<com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.SourceCell>();
      for (var ref : refs) {
        shape.append(source.formula(), end, ref.start()).append('X'); end = ref.end();
        cells.add(last.compiled().sources().stream().filter(c -> c.address().equals(new Address(ref.sheetName(), ref.cellRef()))).findFirst().orElse(null));
      }
      shape.append(source.formula().substring(end));
      if (!shape.toString().replaceAll("\\s+", "").equals("X+X") || cells.contains(null)) continue;
      for (int index = 0; index < 2; index++) {
        var base = cells.get(index); var increment = cells.get(1-index);
        if (previous.compiled().sources().stream().noneMatch(c -> c.address().equals(base.address()))) continue;
        if (rule.extrapolationSources() == null || rule.extrapolationSources().stream()
            .noneMatch(s -> s.address().equals(increment.address()))) continue;
        try {
          var before = beforeRounding(previous);
          for (var multiplier : rule.taxIncluded() ? List.of(BigDecimal.ONE) : List.of(BigDecimal.ONE, VAT)) {
            var delta = new BigDecimal(increment.rawValue());
            if (near(new BigDecimal(base.rawValue()), before.multiply(multiplier))
                && near(delta, proof.priceStep().multiply(multiplier))
                && near(new BigDecimal(source.rawValue()), proof.rounding().anchorBeforeRounding().multiply(multiplier))
                && near(new BigDecimal(source.rawValue()), new BigDecimal(base.rawValue()).add(delta))) return true;
          }
        } catch (NumberFormatException ignored) { /* 不能从非数字依赖推算。 */ }
      }
    }
    return false;
  }

  private static boolean near(BigDecimal left, BigDecimal right) {return left.subtract(right).abs().compareTo(EPS) <= 0;}
}
