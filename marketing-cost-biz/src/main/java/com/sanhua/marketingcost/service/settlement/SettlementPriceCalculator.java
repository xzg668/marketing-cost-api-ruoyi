package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceMode;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** 三种取价方式的唯一算术实现；不查询数据库、不更新规则或基价。 */
@Component
public class SettlementPriceCalculator {
  public static final int UNIT_PRICE_SCALE = 12;
  public static final String LEGACY_CALCULATOR_VERSION = "SETTLEMENT_PRICE_V1";
  public static final String CALCULATOR_VERSION = "SETTLEMENT_PRICE_V2";
  public record Calculation(SettlementPriceMode mode, BigDecimal unitPrice) {}
  public static class CalculationException extends RuntimeException {
    private final String code;
    public CalculationException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
  }

  public SettlementPriceMode mode(SettlementPriceRecord rule) {
    if (rule.getPriceType() == SettlementPriceType.SETTLE_FIXED) return SettlementPriceMode.FIXED_GIVEN;
    if (rule.getPriceType() != SettlementPriceType.SETTLE_LINKED) throw invalid("结算价格类型无效");
    if (!rule.isWeightsConfirmed() || rule.getCopperWeightG() == null || rule.getZincWeightG() == null) {
      if (SettlementFormula.ROUNDED_VERSION.equals(rule.getFormulaVersion())) return SettlementPriceMode.LINKED_FORMULA;
      return SettlementPriceMode.LINKED_GIVEN;
    }
    return SettlementPriceMode.LINKED_FORMULA;
  }

  public Calculation calculate(SettlementPriceRecord rule, SettlementBasePrice base) {
    SettlementPriceMode mode = mode(rule);
    if (rule.getGivenPrice() == null)
      throw new CalculationException("SETTLEMENT_GIVEN_PRICE_MISSING", "结算原稿缺少给定价：" + rule.getMaterialCode());
    nonnegative(rule.getGivenPrice(), "给定价");
    if (mode != SettlementPriceMode.LINKED_FORMULA) return new Calculation(mode, rounded(rule.getGivenPrice()));
    if (!rule.isWeightsConfirmed() || rule.getCopperWeightG() == null || rule.getZincWeightG() == null)
      throw new CalculationException("SETTLEMENT_WEIGHTS_MISSING", "结算联动价原稿铜锌重量不完整，请修正原稿后重新导入：" + rule.getMaterialCode());
    boolean roundedFormula = SettlementFormula.ROUNDED_VERSION.equals(rule.getFormulaVersion());
    validateFormula(rule);
    nonnegative(rule.getPlannedPrice(), "计划价");
    nonnegative(rule.getBudgetCu(), "预算铜价");
    nonnegative(rule.getBudgetZn(), "预算锌价");
    nonnegative(rule.getCopperWeightG(), "计算铜重");
    nonnegative(rule.getZincWeightG(), "计算锌重");
    positive(rule.getMarkupRatio(), "上浮倍率");
    positive(rule.getTaxDivisor(), "税除数");
    if (base == null) throw new CalculationException("SETTLEMENT_BASE_MISSING", "尚未发布适用的商用财务铜锌基价：" + rule.getMaterialCode());
    if (!rule.getBusinessUnitType().equals(base.businessUnitType()) || !base.active()
        || !"CNY/KG".equals(base.unit()) || !"BEFORE_FORMULA_TAX".equals(base.taxBasis()))
      throw invalid("结算基价范围、状态或单位无效");
    nonnegative(base.copperPrice(), "结算铜价");
    nonnegative(base.zincPrice(), "结算锌价");
    BigDecimal adjustment = base.copperPrice().subtract(rule.getBudgetCu()).multiply(rule.getCopperWeightG())
        .add(base.zincPrice().subtract(rule.getBudgetZn()).multiply(rule.getZincWeightG()));
    BigDecimal denominator = new BigDecimal("1000").multiply(rule.getTaxDivisor());
    // 通分后只在最终单价除法舍入，避免中间除税或倍率放大舍入误差。
    BigDecimal numerator = rule.getPlannedPrice().multiply(denominator).add(adjustment).multiply(rule.getMarkupRatio());
    if (numerator.signum() < 0) throw invalid("结算公式计算得到负价格，请核查计划价、重量及基价");
    return new Calculation(mode, rounded(numerator.divide(denominator,
        roundedFormula ? 4 : UNIT_PRICE_SCALE, RoundingMode.HALF_UP)));
  }

  public void validateFormula(SettlementPriceRecord rule) {
    boolean roundedFormula = SettlementFormula.ROUNDED_VERSION.equals(rule.getFormulaVersion());
    if (!(roundedFormula ? SettlementFormula.ROUNDED_EXPRESSION.equals(rule.getExpression())
        : SettlementFormula.VERSION.equals(rule.getFormulaVersion()) && SettlementFormula.EXPRESSION.equals(rule.getExpression())))
      throw new CalculationException("SETTLEMENT_FORMULA_INVALID", "结算公式版本或表达式无效：" + rule.getMaterialCode());
  }

  private BigDecimal rounded(BigDecimal value) {
    var result = value.setScale(UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    if (result.precision() > 24) throw invalid("结算单价超出核算允许的金额范围");
    return result;
  }
  private void positive(BigDecimal value, String label) {
    nonnegative(value, label);
    if (value.signum() == 0) throw invalid(label + "必须大于 0");
  }
  private void nonnegative(BigDecimal value, String label) {
    if (value == null || value.signum() < 0) throw invalid(label + "必须为非负数");
  }
  private CalculationException invalid(String message) { return new CalculationException("SETTLEMENT_INPUT_INVALID", message); }
}
