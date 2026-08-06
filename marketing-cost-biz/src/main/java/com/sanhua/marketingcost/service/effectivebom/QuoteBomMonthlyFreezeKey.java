package com.sanhua.marketingcost.service.effectivebom;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;

/** 客户场景月度最终 BOM 的唯一冻结键。 */
public record QuoteBomMonthlyFreezeKey(
    String costPeriodMonth,
    String productCode,
    String resolvedCustomerKey,
    String packageMethod,
    String priceOrgCode) {

  public QuoteBomMonthlyFreezeKey {
    costPeriodMonth = normalizeMonth(costPeriodMonth);
    productCode = requireText(productCode, "产品料号", 64);
    resolvedCustomerKey = requireText(resolvedCustomerKey, "客户隔离键", 128);
    packageMethod = normalizePackageMethod(packageMethod);
    if (packageMethod.length() > 128) {
      throw new IllegalArgumentException("包装方式不能超过128字符");
    }
    priceOrgCode = requireText(priceOrgCode, "U9价格组织", 32);
  }

  private static String normalizeMonth(String value) {
    String normalized = requireText(value, "核算月份", 7);
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("核算月份格式错误，应为YYYY-MM: " + normalized, ex);
    }
  }

  private static String normalizePackageMethod(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String normalized = value.trim();
    return "/".equals(normalized) ? "" : normalized;
  }

  private static String requireText(String value, String field, int maxLength) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + "不能超过" + maxLength + "字符");
    }
    return normalized;
  }
}
