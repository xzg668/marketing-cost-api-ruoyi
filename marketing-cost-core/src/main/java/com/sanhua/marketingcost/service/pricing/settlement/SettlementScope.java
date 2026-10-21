package com.sanhua.marketingcost.service.pricing.settlement;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** 结算身份不包含消费组织；月份由业务明确指定，不能使用上传时间替代。 */
public record SettlementScope(String businessUnitType, String priceMonth) {
  public SettlementScope {
    businessUnitType = required(businessUnitType, "业务单元", 32).toUpperCase(Locale.ROOT);
    if (!businessUnitType.equals("COMMERCIAL") && !businessUnitType.equals("HOUSEHOLD"))
      throw new IllegalArgumentException("业务单元仅支持 COMMERCIAL/HOUSEHOLD");
    priceMonth = required(priceMonth, "结算月份", 7);
    if (!priceMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
      throw new IllegalArgumentException("结算月份必须为 yyyy-MM");
    try { YearMonth.parse(priceMonth); }
    catch (DateTimeParseException invalid) { throw new IllegalArgumentException("结算月份无效", invalid); }
  }

  public static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank() || value.trim().length() > maxLength)
      throw new IllegalArgumentException(label + "必填且长度不能超过" + maxLength);
    return value.trim();
  }
}
