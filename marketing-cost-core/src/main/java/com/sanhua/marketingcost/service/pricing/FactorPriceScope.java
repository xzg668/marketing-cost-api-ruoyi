package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;

/** 精确月份的财务输入范围；历史月份回退由选价服务决定，不能藏在因素查询中。 */
public record FactorPriceScope(
    String businessUnitType, String priceOrgCode, String priceMonth,
    String schemeCode, Condition condition) {

  public enum Condition { GENERAL, NON_TENDER, TENDER }

  public FactorPriceScope {
    businessUnitType = required(businessUnitType, "业务单元", 32).toUpperCase(Locale.ROOT);
    if (!businessUnitType.equals("COMMERCIAL") && !businessUnitType.equals("HOUSEHOLD")) {
      throw new IllegalArgumentException("业务单元仅支持 COMMERCIAL/HOUSEHOLD");
    }
    priceOrgCode = MaterialOrganization.fromPriceOrgCode(priceOrgCode).getPriceOrgCode();
    priceMonth = month(priceMonth);
    schemeCode = required(schemeCode, "财务方案", 64).toUpperCase(Locale.ROOT);
    if (!schemeCode.matches("[A-Z0-9][A-Z0-9_-]*") || schemeCode.equals("LEGACY")) {
      throw new IllegalArgumentException("财务方案必须使用明确的方案编码，不能使用 LEGACY");
    }
    Objects.requireNonNull(condition, "因素价格条件必填");
    if (priceOrgCode.equals("210") && condition != Condition.GENERAL) {
      throw new IllegalArgumentException("商用基价不使用板换招标条件");
    }
  }

  static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank() || value.trim().length() > maxLength) {
      throw new IllegalArgumentException(label + "必填且长度不能超过" + maxLength);
    }
    return value.trim();
  }

  public static String month(String value) {
    String normalized = required(value, "价格月份", 7);
    if (!normalized.matches("[0-9]{4}-[0-9]{2}")) {
      throw new IllegalArgumentException("价格月份格式必须为 yyyy-MM");
    }
    try {
      YearMonth.parse(normalized);
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new IllegalArgumentException("价格月份格式必须为 yyyy-MM", invalid);
    }
    return normalized;
  }
}
