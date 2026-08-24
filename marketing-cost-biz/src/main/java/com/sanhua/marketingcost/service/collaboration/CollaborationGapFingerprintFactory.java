package com.sanhua.marketingcost.service.collaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 业务缺口统一指纹：报价行、月份、缺口、物料、BOM位置和来源共同决定。 */
public final class CollaborationGapFingerprintFactory {

  private CollaborationGapFingerprintFactory() {}

  public static String create(
      Long oaFormItemId,
      String accountingMonth,
      String gapCategory,
      String gapType,
      String materialCode,
      String businessPosition,
      String sourceFingerprint) {
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("协作缺口指纹缺少报价产品行ID");
    }
    String canonical = String.join("\n",
        "ITEM=" + oaFormItemId,
        "MONTH=" + required(accountingMonth, "核算月份"),
        "CATEGORY=" + required(gapCategory, "缺口分类"),
        "TYPE=" + required(gapType, "缺口类型"),
        "MATERIAL=" + optional(materialCode),
        "POSITION=" + optional(businessPosition),
        "SOURCE=" + optional(sourceFingerprint));
    return sha256(canonical);
  }

  private static String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static String optional(String value) {
    return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }
}
