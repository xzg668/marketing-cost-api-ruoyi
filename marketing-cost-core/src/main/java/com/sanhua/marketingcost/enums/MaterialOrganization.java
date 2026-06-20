package com.sanhua.marketingcost.enums;

import java.util.Locale;
import org.springframework.util.StringUtils;

/** U9 料品主档组织维度，独立于报价业务单元。 */
public enum MaterialOrganization {
  COMMERCIAL("COMMERCIAL", "商用"),
  PLATE("PLATE", "板换");

  private final String code;
  private final String label;

  MaterialOrganization(String code, String label) {
    this.code = code;
    this.label = label;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return COMMERCIAL.code;
    }
    String normalized = value.trim();
    for (MaterialOrganization organization : values()) {
      if (organization.code.equalsIgnoreCase(normalized)
          || organization.label.equals(normalized)) {
        return organization.code;
      }
    }
    throw new IllegalArgumentException("料品组织仅支持 COMMERCIAL(商用) 或 PLATE(板换)");
  }

  /**
   * 报价单料品组织规则：FI-SC-020 是板换流程；产品名称包含板换或换热水器时也按板换处理。
   *
   * <p>processCode 通常是模板编码 FI-SC-020；oaNo 通常是完整流程编号
   * FI-SC-020-YYYYMMDD-NNN。两者都兼容 FISC020 写法。
   */
  public static String forQuoteProcess(String processCode, String oaNo) {
    return forQuoteProcess(processCode, oaNo, null);
  }

  public static String forQuoteProcess(String processCode, String oaNo, String productName) {
    String process = firstText(processCode, oaNo);
    if (isPlateProcess(process) || isPlateProductName(productName)) {
      return PLATE.code;
    }
    return COMMERCIAL.code;
  }

  private static boolean isPlateProcess(String process) {
    if (!StringUtils.hasText(process)) {
      return false;
    }
    String normalized = process.trim().toUpperCase(Locale.ROOT);
    String compact = normalized.replace("-", "").replace("_", "");
    return normalized.startsWith("FI-SC-020") || compact.startsWith("FISC020");
  }

  private static boolean isPlateProductName(String productName) {
    if (!StringUtils.hasText(productName)) {
      return false;
    }
    String normalized = productName.trim();
    return normalized.contains("板换") || normalized.contains("换热水器");
  }

  private static String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }
}
