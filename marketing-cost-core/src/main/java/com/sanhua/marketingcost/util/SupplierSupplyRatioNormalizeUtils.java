package com.sanhua.marketingcost.util;

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class SupplierSupplyRatioNormalizeUtils {
  private static final Pattern ALL_BLANK_CHARS = Pattern.compile("[\\s\\u3000]+");

  private SupplierSupplyRatioNormalizeUtils() {}

  /** 供货比例导入去重前统一去掉所有空白字符，避免多人多次导入时隐藏空格造重复数据。 */
  public static String normalizeKeyPart(String value) {
    if (value == null) {
      return "";
    }
    return ALL_BLANK_CHARS.matcher(value.trim()).replaceAll("");
  }

  public static String normalizeToNull(String value) {
    String normalized = normalizeKeyPart(value);
    return StringUtils.hasText(normalized) ? normalized : null;
  }

  /** 有代码按代码识别；缺少代码时，按完整供应商名称识别，不生成虚构代码。 */
  public static String supplierIdentity(String code, String name) {
    String normalizedCode = normalizeToNull(code);
    if (normalizedCode != null) return "CODE:" + normalizedCode;
    String normalizedName = normalizeToNull(name);
    if (normalizedName == null) throw new IllegalArgumentException("供应商名称不能为空");
    return "NAME:" + normalizedName;
  }

}
