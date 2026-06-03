package com.sanhua.marketingcost.util;

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public final class SupplierSupplyRatioNormalizeUtils {
  private static final Pattern ALL_BLANK_CHARS = Pattern.compile("[\\s\\u3000]+");
  private static final String KEY_SEPARATOR = "|";

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

  public static String buildDedupeKey(
      String materialCode, String materialName, String supplierName, String specModel) {
    return normalizeKeyPart(materialCode)
        + KEY_SEPARATOR
        + normalizeKeyPart(supplierName);
  }
}
