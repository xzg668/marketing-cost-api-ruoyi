package com.sanhua.marketingcost.service.ingest;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 OA 单号中提取流程类型编码，例如 FI-SC-006-20260327-037 -> FI-SC-006。 */
final class QuoteProcessCodeResolver {
  private static final Pattern PROCESS_CODE_PREFIX = Pattern.compile("^(FI-[A-Z]{2}-\\d{3})(?:\\b|-|_)?.*");

  private QuoteProcessCodeResolver() {}

  static String resolve(String processCode, String oaNo, String externalFormNo) {
    String fromProcessCode = normalizeProcessCode(processCode);
    if (fromProcessCode != null) {
      return fromProcessCode;
    }
    String fromOaNo = normalizeProcessCode(oaNo);
    if (fromOaNo != null) {
      return fromOaNo;
    }
    return normalizeProcessCode(externalFormNo);
  }

  static String normalizeProcessCode(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    Matcher matcher = PROCESS_CODE_PREFIX.matcher(upper);
    return matcher.matches() ? matcher.group(1) : upper;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
