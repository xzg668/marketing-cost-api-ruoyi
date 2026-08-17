package com.sanhua.marketingcost.support;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 制造费用率匹配键的统一规范。 */
public final class ManufactureRateMatchSupport {
  public static final String MATCH_LEVEL_DIVISION_CATEGORY_PREFIX =
      "DIVISION_CATEGORY_PREFIX";
  public static final String MATCH_KEY_SEPARATOR = "::";

  private static final Pattern LEADING_LATIN_PREFIX = Pattern.compile("^([A-Za-z]+)");

  private ManufactureRateMatchSupport() {}

  /**
   * 从“J系列”“J11”“S95及以上”等值提取可匹配的系列前缀。
   *
   * <p>只接受开头的拉丁字母，避免把“除以上特殊型号外……”等说明文字误建成产品大类规则。
   */
  public static String categoryPrefix(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    Matcher matcher = LEADING_LATIN_PREFIX.matcher(value.trim());
    return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
  }

  public static String divisionCategoryKey(String businessDivision, String categoryPrefix) {
    if (!StringUtils.hasText(businessDivision) || !StringUtils.hasText(categoryPrefix)) {
      return null;
    }
    return businessDivision.trim()
        + MATCH_KEY_SEPARATOR
        + categoryPrefix.trim().toUpperCase(Locale.ROOT);
  }
}
