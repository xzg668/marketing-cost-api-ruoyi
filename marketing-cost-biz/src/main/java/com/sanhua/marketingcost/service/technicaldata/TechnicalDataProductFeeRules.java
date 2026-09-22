package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ProductFees;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 单件费用统一规则。零只表示用户明确无费用，null 表示尚未填写。 */
public final class TechnicalDataProductFeeRules {
  private TechnicalDataProductFeeRules() {}
  public record Issue(String field, String message) {}

  public static ProductFees parse(Boolean included, String tooling, String mould, String certification) {
    if (included == null) throw new IllegalArgumentException("请选择是否含新增工装模具认证费");
    return new ProductFees(included, parseAmount(tooling, included, "工装费"),
        parseAmount(mould, included, "模具费"), parseAmount(certification, included, "认证费"), "CNY");
  }

  private static BigDecimal parseAmount(String value, boolean required, String label) {
    String text = value == null ? "" : value.trim();
    if ("/".equals(text) || text.isEmpty() && !required) return BigDecimal.ZERO;
    if (text.isEmpty()) throw new IllegalArgumentException(label + "请填写单件金额或 /");
    if (!text.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) {
      throw new IllegalArgumentException(label + "须为正金额或 /，最多 12 位整数、6 位小数");
    }
    BigDecimal amount = new BigDecimal(text);
    if (amount.signum() <= 0) throw new IllegalArgumentException(label + "须大于 0，无费用请填写 /");
    return amount.stripTrailingZeros();
  }

  public static List<Issue> validate(ProductFees fees) {
    List<Issue> issues = new ArrayList<>();
    if (fees == null || fees.includesNewToolingMouldCertificationFee() == null) {
      issues.add(new Issue("hasAdditionalFees", "请选择是否含新增工装模具认证费"));
    }
    if (fees != null) {
      validateAmount("unitToolingFee", "工装费", fees.unitToolingFee(), issues);
      validateAmount("unitMouldFee", "模具费", fees.unitMouldFee(), issues);
      validateAmount("unitCertificationFee", "认证费", fees.unitCertificationFee(), issues);
      if (!"CNY".equals(fees.currency())) issues.add(new Issue("currency", "单件费用应以人民币元/件填写"));
    }
    return List.copyOf(issues);
  }

  private static void validateAmount(String field, String label, BigDecimal amount, List<Issue> issues) {
    if (amount == null || amount.signum() < 0 || amount.stripTrailingZeros().scale() > 6
        || amount.precision() - amount.scale() > 12) {
      issues.add(new Issue(field, label + "须填写合法的单件金额或 /"));
    }
  }

  public static com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.ProductFees costingInput(ProductFees fees) {
    if (fees == null) return null;
    List<Issue> issues = validate(fees);
    if (!issues.isEmpty()) throw new IllegalArgumentException(issues.getFirst().message());
    return new com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.ProductFees(
        fees.includesNewToolingMouldCertificationFee(), fees.unitToolingFee(), fees.unitMouldFee(),
        fees.unitCertificationFee(), fees.currency());
  }

  public static String display(BigDecimal value) {
    return value == null ? null : value.signum() == 0 ? "/" : value.stripTrailingZeros().toPlainString();
  }
}
