package com.sanhua.marketingcost.service.pricing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 废料供应商可为空；报价组织不是废料价格身份的一部分。 */
public record ScrapPriceIdentity(
    String businessUnit,
    String month,
    String materialCode,
    String supplierCode,
    String supplierName) {
  public ScrapPriceIdentity {
    businessUnit = businessUnit(businessUnit);
    month = FactorPriceScope.month(month);
    materialCode = FactorPriceScope.required(materialCode, "废料料号", 64);
    supplierCode = optional(supplierCode, "供应商代码", 64);
    supplierName = optional(supplierName, "供应商名称", 255);
  }

  public String supplierKey() {
    return supplierKey(supplierCode, supplierName);
  }

  public static String supplierKey(String code, String name) {
    code = optional(code, "供应商代码", 64);
    name = optional(name, "供应商名称", 255);
    return hash(code != null ? "CODE:" + code : name != null ? "NAME:" + name : "COMMON");
  }

  public static String businessUnit(String value) {
    String unit = FactorPriceScope.required(value, "业务单元", 32).toUpperCase(Locale.ROOT);
    if (!unit.equals("COMMERCIAL") && !unit.equals("HOUSEHOLD"))
      throw new IllegalArgumentException("业务单元仅支持COMMERCIAL/HOUSEHOLD");
    return unit;
  }

  /** 长度前缀避免料号中分隔符引起身份碰撞。 */
  public String materialSupplierKey() {
    return materialCode.length() + ":" + materialCode + ":" + supplierKey();
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM不支持SHA-256", impossible);
    }
  }

  private static String optional(String value, String label, int length) {
    if (value == null || value.isBlank()) return null;
    return FactorPriceScope.required(value, label, length);
  }
}
