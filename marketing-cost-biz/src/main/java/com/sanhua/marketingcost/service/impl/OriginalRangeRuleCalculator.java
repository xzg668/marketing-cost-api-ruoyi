package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Band;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Bounds;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Rule;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** P0/P1固定同一规则，以实际边界重新选档。没有可靠档差证据时明确缺档。 */
@Component
public final class OriginalRangeRuleCalculator {
  private static final BigDecimal VAT = new BigDecimal("1.13");
  private static final BigDecimal FLOAT_TOLERANCE = new BigDecimal("0.000000000001");

  public Result calculate(Rule rule, PriceFactorContext context) {
    var key = rule.identity();
    var scope = context.financial().scope();
    if (!key.materialCode().equals(context.materialCode())
        || !key.businessUnitType().equals(scope.businessUnitType())
        || !key.orgCode().equals(scope.priceOrgCode()))
      throw new IllegalArgumentException("区间规则与财务因素的料号/业务单元/组织不一致");
    return select(rule, context.requiredPrice(rule.factorKey(), rule.factorUnit(), true));
  }

  public Result select(Rule rule, BigDecimal factorValue) {
    if (factorValue == null) throw new IllegalArgumentException("区间行情因素缺失");
    List<Band> hits = rule.bands().stream().filter(b -> b.bounds().contains(factorValue)).toList();
    if (hits.size() > 1) throw problem(rule, "命中重叠档：" + factorValue);
    if (hits.size() == 1) {
      Band band = hits.getFirst();
      if (band.problem() != null || band.price() == null)
        throw problem(rule, "选中档价格不可用：" + band.address() + "，" + band.problem());
      return result(rule, factorValue, band.price(), band.bounds(), band, null);
    }
    Extrapolation proof = extrapolation(rule);
    if (proof == null || factorValue.compareTo(proof.anchor().bounds().high()) < 0)
      throw problem(rule, "缺少行情档位且无可靠向上推算依据：" + factorValue);
    BigDecimal steps =
        factorValue
            .subtract(proof.anchor().bounds().low())
            .divide(proof.factorStep(), 0, RoundingMode.FLOOR);
    if (steps.signum() <= 0) throw problem(rule, "缺少行情档位：" + factorValue);
    BigDecimal shift = steps.multiply(proof.factorStep());
    var anchor = proof.anchor().bounds();
    var selected =
        new Bounds(
            anchor.low().add(shift),
            anchor.high().add(shift),
            anchor.lowerInclusive(),
            anchor.upperInclusive());
    // 整数闭区间间的小数间隙仍然是缺档，不能因推算而补齐原稿未定义的边界。
    if (!selected.contains(factorValue)) throw problem(rule, "推算档间存在缺档：" + factorValue);
    BigDecimal price = proof.anchor().price().add(proof.priceStep().multiply(steps));
    return result(rule, factorValue, price, selected, proof.anchor(), proof);
  }

  public Extrapolation extrapolation(Rule rule) {
    var bands = rule.bands().stream().sorted(Comparator.comparing(b -> b.bounds().low())).toList();
    if (bands.size() < 4) return null;
    // 要求整段连续有效档位具有相同边界步长和单价档差；不对舍入后交替档差作线性猜测。
    int start = 0;
    for (int i = 0; i < bands.size(); i++)
      if (bands.get(i).price() == null || bands.get(i).problem() != null) start = i + 1;
    if (bands.size() - start < 4) return null;
    var evidence = bands.subList(start, bands.size());
    Extrapolation full = stableBands(rule, evidence, "CONTIGUOUS_ORIGINAL_BANDS");
    if (full != null) return full;
    // 部分原稿低价表头有错误。仅当高档连续稳定、且原稿文字步长与本材料档差格
    // 同时印证时，允许使用高档段；不能只截取最后几个相似值猜测外推。
    for (int offset = 1; offset <= evidence.size() - 4; offset++) {
      Extrapolation tail =
          stableBands(
              rule, evidence.subList(offset, evidence.size()), "SOURCE_NOTE_AND_STABLE_HIGH_BANDS");
      if (tail != null && sourceConfirms(rule, tail)) return tail;
    }
    return null;
  }

  private boolean sourceConfirms(Rule rule, Extrapolation proof) {
    if (rule.extrapolationSources() == null) return false;
    String step = proof.factorStep().stripTrailingZeros().toPlainString();
    var number =
        java.util.regex.Pattern.compile(
            "(?<![0-9.])" + java.util.regex.Pattern.quote(step) + "(?![0-9.])");
    boolean note =
        rule.extrapolationSources().stream()
            .anyMatch(
                source ->
                    (source.rawValue().contains("上涨")
                            || source.rawValue().contains("下降")
                            || source.rawValue().contains("下浮"))
                        && number.matcher(source.rawValue()).find());
    boolean delta =
        rule.extrapolationSources().stream()
            .anyMatch(
                source -> {
                  try {
                    BigDecimal value = new BigDecimal(source.rawValue());
                    return value.subtract(proof.priceStep()).abs().compareTo(FLOAT_TOLERANCE) <= 0
                        || !rule.taxIncluded()
                            && value
                                    .subtract(proof.priceStep().multiply(VAT))
                                    .abs()
                                    .compareTo(FLOAT_TOLERANCE)
                                <= 0;
                  } catch (NumberFormatException error) {
                    return false;
                  }
                });
    return note && delta;
  }

  private Extrapolation stableBands(Rule rule, List<Band> evidence, String basis) {
    Band first = evidence.getFirst(), last = evidence.getLast();
    BigDecimal step = evidence.get(1).bounds().low().subtract(first.bounds().low());
    if (step.signum() <= 0) return null;
    BigDecimal delta =
        last.price()
            .subtract(first.price())
            .divide(BigDecimal.valueOf(evidence.size() - 1L), MathContext.DECIMAL128);
    if (delta.signum() < 0) return null;
    BigDecimal width = first.bounds().high().subtract(first.bounds().low());
    for (int i = 0; i < evidence.size(); i++) {
      Band band = evidence.get(i);
      var bounds = band.bounds();
      BigDecimal n = BigDecimal.valueOf(i);
      if (bounds.low().compareTo(first.bounds().low().add(step.multiply(n))) != 0
          || bounds.high().subtract(bounds.low()).compareTo(width) != 0
          || bounds.lowerInclusive() != first.bounds().lowerInclusive()
          || bounds.upperInclusive() != first.bounds().upperInclusive()
          || band.price()
                  .subtract(first.price().add(delta.multiply(n)))
                  .abs()
                  .compareTo(FLOAT_TOLERANCE)
              > 0) return null;
    }
    if (width.compareTo(step) > 0
        || width.compareTo(step) == 0
            && first.bounds().lowerInclusive()
            && first.bounds().upperInclusive()) return null;
    return new Extrapolation(
        last,
        step,
        delta,
        rule.taxIncluded(),
        List.copyOf(evidence),
        basis,
        rule.extrapolationSources() == null ? List.of() : rule.extrapolationSources());
  }

  private Result result(
      Rule rule,
      BigDecimal factor,
      BigDecimal price,
      Bounds bounds,
      Band band,
      Extrapolation proof) {
    if (price.signum() < 0) throw problem(rule, "推算单价不能为负数");
    return new Result(
        factor,
        price,
        rule.taxIncluded() ? price.divide(VAT, MathContext.DECIMAL128) : price,
        bounds,
        band,
        proof);
  }

  private IllegalArgumentException problem(Rule rule, String message) {
    return new IllegalArgumentException(
        "物料="
            + rule.identity().materialCode()
            + "，供应商="
            + rule.identity().supplierName()
            + "，"
            + rule.matrixSheet()
            + "第"
            + rule.matrixRow()
            + "行："
            + message);
  }

  public record Extrapolation(
      Band anchor,
      BigDecimal factorStep,
      BigDecimal priceStep,
      boolean taxIncluded,
      List<Band> evidence,
      String basis,
      List<OriginalRangeWorkbookParser.CellSource> supportingSources) {}

  public record Result(
      BigDecimal factorValue,
      BigDecimal sourcePrice,
      BigDecimal priceExcludingTax,
      Bounds selectedBounds,
      Band sourceBand,
      Extrapolation extrapolation) {}
}
