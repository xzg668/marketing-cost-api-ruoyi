package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Pure input and stored-value semantics for the four optional unit fees. */
public final class ProductFeeContract {
  public static final int LEGACY_ENCODING_VERSION = 1;
  public static final int CURRENT_ENCODING_VERSION = 2;
  public static final String POLICY_VERSION = "FEE_PROFILE_OPTIONAL_V2";

  private static final int MAX_INTEGER_DIGITS = 12;
  private static final int MAX_FRACTION_DIGITS = 6;
  private static final Pattern AMOUNT_INPUT = Pattern.compile(
      "[0-9]{1," + MAX_INTEGER_DIGITS + "}(\\.[0-9]{1," + MAX_FRACTION_DIGITS + "})?");

  private ProductFeeContract() {}

  public enum State {
    MISSING,
    EXPLICIT_NONE,
    EXPLICIT_AMOUNT,
    LEGACY
  }

  /** Empty input remains absent; a slash is an explicit declaration of no fee. */
  public static BigDecimal parseAmount(String input) {
    String value = input == null ? "" : input.strip();
    if (value.isEmpty()) return null;
    if ("/".equals(value)) return BigDecimal.ZERO;
    if (!AMOUNT_INPUT.matcher(value).matches()) {
      throw new IllegalArgumentException("单件费用须为非负金额或 /，最多 12 位整数、6 位小数");
    }
    return new BigDecimal(value).stripTrailingZeros();
  }

  /** Interpret stored values without assigning a new encoding to legacy content. */
  public static State state(BigDecimal amount, Integer encodingVersion) {
    int encoding = encodingVersion == null ? LEGACY_ENCODING_VERSION : encodingVersion;
    if (encoding != LEGACY_ENCODING_VERSION && encoding != CURRENT_ENCODING_VERSION) {
      throw new IllegalArgumentException("不支持的产品费用编码版本：" + encoding);
    }
    if (amount == null) return State.MISSING;
    BigDecimal normalized = amount.stripTrailingZeros();
    if (normalized.signum() < 0 || normalized.scale() > MAX_FRACTION_DIGITS
        || (long) normalized.precision() - normalized.scale() > MAX_INTEGER_DIGITS) {
      throw new IllegalArgumentException("已保存的产品单件费用超出非负、12 位整数、6 位小数的合法范围");
    }
    if (encoding == LEGACY_ENCODING_VERSION) return State.LEGACY;
    return amount.signum() == 0 ? State.EXPLICIT_NONE : State.EXPLICIT_AMOUNT;
  }
}
