package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;

/** 同月活动任务的唯一键；只使用稳定业务维度，不暴露原始料号。 */
public final class CollaborationActiveLockKeyFactory {

  private static final String PREFIX = "QCBP-ACTIVE-V2:";

  private CollaborationActiveLockKeyFactory() {}

  public static String create(
      String accountingMonth,
      String productCode,
      String temporaryProductKey,
      CollaborationScope scope,
      PrimaryScope primaryScope) {
    String month = normalizeMonth(accountingMonth);
    String product = normalizeOptional(productCode);
    String temporary = normalizeOptional(temporaryProductKey);
    if (product == null && temporary == null) {
      throw new IllegalArgumentException("产品料号或临时产品键至少填写一个");
    }
    if (scope == null) {
      throw new IllegalArgumentException("协作范围不能为空");
    }
    if (primaryScope == null) {
      throw new IllegalArgumentException("主要缺口范围不能为空");
    }
    String identityType = product == null ? "TEMP" : "PRODUCT";
    String identity = product == null ? temporary : product;
    String canonical = String.join("\n",
        "VERSION=2",
        "MONTH=" + month,
        "IDENTITY_TYPE=" + identityType,
        "IDENTITY=" + identity,
        "BUSINESS_UNIT=" + normalizeRequired(scope.businessUnitType()),
        "ORG=" + normalizeRequired(scope.applicableOrgCode()));
    return PREFIX + sha256(canonical);
  }

  private static String normalizeMonth(String value) {
    try {
      return YearMonth.parse(CollaborationScope.requireText(value, "核算月份")).toString();
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("核算月份格式错误，应为YYYY-MM", exception);
    }
  }

  private static String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : normalizeRequired(value);
  }

  private static String normalizeRequired(String value) {
    return CollaborationScope.requireText(value, "锁键维度").toUpperCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }
}
