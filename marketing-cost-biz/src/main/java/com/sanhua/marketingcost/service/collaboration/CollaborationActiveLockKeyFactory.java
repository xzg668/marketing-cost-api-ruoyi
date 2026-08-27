package com.sanhua.marketingcost.service.collaboration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 活动补录任务的唯一键。
 *
 * <p>锁不带报价单和核算月份：同一组织下，有料号按料号锁；无料号按型号锁；连型号也没有时
 * 才按报价产品行生成的稳定临时键锁。这样同一产品跨报价、跨月份只能有一个进行中的补录任务。
 */
public final class CollaborationActiveLockKeyFactory {

  private static final String PREFIX = "QCBP-ACTIVE-V3:";

  private CollaborationActiveLockKeyFactory() {}

  public static String create(
      String productCode,
      String productModel,
      String temporaryProductKey,
      CollaborationScope scope) {
    String product = normalizeOptional(productCode);
    String model = normalizeOptional(productModel);
    String temporary = normalizeOptional(temporaryProductKey);
    if (product == null && model == null && temporary == null) {
      throw new IllegalArgumentException("产品料号、型号或临时产品键至少填写一个");
    }
    if (scope == null) {
      throw new IllegalArgumentException("协作范围不能为空");
    }
    String identityType = product != null ? "PRODUCT" : model != null ? "MODEL" : "TEMP";
    String identity = product != null ? product : model != null ? model : temporary;
    String canonical = String.join("\n",
        "VERSION=3",
        "IDENTITY_TYPE=" + identityType,
        "IDENTITY=" + identity,
        "BUSINESS_UNIT=" + normalizeRequired(scope.businessUnitType()),
        "ORG=" + normalizeRequired(scope.applicableOrgCode()));
    return PREFIX + sha256(canonical);
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
