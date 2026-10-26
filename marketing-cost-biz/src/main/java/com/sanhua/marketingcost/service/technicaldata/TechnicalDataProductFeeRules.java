package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ProductFees;
import com.sanhua.marketingcost.service.costing.ProductFeeContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** PROFILE transport and stored-content adapter for the shared optional unit-fee contract. */
public final class TechnicalDataProductFeeRules {
  private TechnicalDataProductFeeRules() {}
  public record Issue(String field, String message) {}

  public static ProductFees parse(Boolean included, String tooling, String mould,
      String certification, String depreciation) {
    return new ProductFees(included, parseAmount(tooling, "工装夹具费"),
        parseAmount(mould, "模具费"), parseAmount(certification, "认证费"), "CNY",
        parseAmount(depreciation, "折旧费"), ProductFeeContract.CURRENT_ENCODING_VERSION);
  }

  private static BigDecimal parseAmount(String value, String label) {
    try {
      return ProductFeeContract.parseAmount(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(label + "：" + error.getMessage(), error);
    }
  }

  public static List<Issue> validate(ProductFees fees) {
    if (fees == null) return List.of();
    List<Issue> issues = new ArrayList<>();
    validateAmount("unitToolingFee", "工装夹具费", fees.unitToolingFee(), fees.encodingVersion(), issues);
    validateAmount("unitMouldFee", "模具费", fees.unitMouldFee(), fees.encodingVersion(), issues);
    validateAmount("unitCertificationFee", "认证费", fees.unitCertificationFee(), fees.encodingVersion(), issues);
    validateAmount("unitDepreciationFee", "折旧费", fees.unitDepreciationFee(), fees.encodingVersion(), issues);
    if (!"CNY".equals(fees.currency())) issues.add(new Issue("currency", "单件费用应以人民币元/件填写"));
    return List.copyOf(issues);
  }

  private static void validateAmount(String field, String label, BigDecimal amount,
      Integer encodingVersion, List<Issue> issues) {
    try {
      ProductFeeContract.state(amount, encodingVersion);
    } catch (IllegalArgumentException error) {
      issues.add(new Issue(field, label + "：" + error.getMessage()));
    }
  }

  public static com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.ProductFees costingInput(ProductFees fees) {
    if (fees == null) return null;
    List<Issue> issues = validate(fees);
    if (!issues.isEmpty()) throw new IllegalArgumentException(issues.getFirst().message());
    return new com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput.ProductFees(
        fees.includesNewToolingMouldCertificationFee(), fees.unitToolingFee(), fees.unitMouldFee(),
        fees.unitCertificationFee(), fees.currency(), fees.unitDepreciationFee(), fees.encodingVersion());
  }

  public static String display(BigDecimal value) {
    return value == null ? null : value.signum() == 0 ? "/" : value.stripTrailingZeros().toPlainString();
  }
}
