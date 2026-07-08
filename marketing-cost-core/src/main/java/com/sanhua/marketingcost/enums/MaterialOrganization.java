package com.sanhua.marketingcost.enums;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** U9 料品主档组织维度，独立于报价业务单元。 */
public enum MaterialOrganization {
  COMMERCIAL("COMMERCIAL", "商用", "210"),
  PLATE("PLATE", "板换", "220");

  private final String code;
  private final String label;
  private final String priceOrgCode;

  MaterialOrganization(String code, String label, String priceOrgCode) {
    this.code = code;
    this.label = label;
    this.priceOrgCode = priceOrgCode;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public String getPriceOrgCode() {
    return priceOrgCode;
  }

  public QuoteDataOrganization toQuoteDataOrganization() {
    return new QuoteDataOrganization(priceOrgCode, code);
  }

  public static QuoteDataOrganization normalizeQuoteDataOrganization(
      QuoteDataOrganization organization) {
    if (organization == null) {
      throw new IllegalArgumentException("报价组织和料品组织必须由上游显式传入");
    }
    MaterialOrganization byMaterialCode = fromCodeOrNull(organization.materialOrganizationCode());
    MaterialOrganization byPriceOrgCode = fromPriceOrgCodeOrNull(organization.priceOrgCode());
    if (byMaterialCode != null && byPriceOrgCode != null && byMaterialCode != byPriceOrgCode) {
      throw new IllegalArgumentException("报价组织和料品组织不匹配");
    }
    if (byMaterialCode == null && byPriceOrgCode == null) {
      throw new IllegalArgumentException("报价组织和料品组织必须由上游显式传入");
    }
    MaterialOrganization resolved =
        byMaterialCode != null
            ? byMaterialCode
            : byPriceOrgCode;
    return resolved.toQuoteDataOrganization();
  }

  public static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException("料品组织必须由上游显式传入");
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

  public static MaterialOrganization fromCode(String value) {
    MaterialOrganization organization = fromCodeOrNull(value);
    if (organization == null) {
      throw new IllegalArgumentException("料品组织必须由上游显式传入");
    }
    return organization;
  }

  public static MaterialOrganization fromPriceOrgCode(String value) {
    MaterialOrganization organization = fromPriceOrgCodeOrNull(value);
    if (organization == null) {
      throw new IllegalArgumentException("BOM报价组织必须由上游显式传入");
    }
    return organization;
  }

  /**
   * 报价单料品组织规则：FI-SC-020 是板换专用流程；其他流程必须由报价明细显式传入组织。
   *
   * <p>processCode 通常是模板编码 FI-SC-020；oaNo 通常是完整流程编号
   * FI-SC-020-YYYYMMDD-NNN。两者都兼容 FISC020 写法。
   */
  public static String forQuoteProcess(String processCode, String oaNo) {
    return forQuoteProcess(processCode, oaNo, null);
  }

  public static String forQuoteProcess(
      String processCode, String oaNo, String materialOrganizationCode) {
    return quoteDataForQuoteProcess(processCode, oaNo, materialOrganizationCode)
        .materialOrganizationCode();
  }

  public static QuoteDataOrganization quoteDataForQuoteProcess(String processCode, String oaNo) {
    return quoteDataForQuoteProcess(processCode, oaNo, null);
  }

  public static QuoteDataOrganization quoteDataForQuoteProcess(
      String processCode, String oaNo, String materialOrganizationCode) {
    return quoteDataForQuoteProcess(processCode, oaNo, materialOrganizationCode, null);
  }

  public static QuoteDataOrganization quoteDataForCurrentContext(
      String processCode, String oaNo, String materialOrganizationCode) {
    return quoteDataForQuoteProcess(
        processCode, oaNo, materialOrganizationCode, BusinessUnitContext.getCurrentBusinessUnitType());
  }

  public static QuoteDataOrganization quoteDataForQuoteProcess(
      String processCode, String oaNo, String materialOrganizationCode, String contextOrganizationCode) {
    return resolveQuoteOrganization(processCode, oaNo, materialOrganizationCode, contextOrganizationCode)
        .toQuoteDataOrganization();
  }

  private static MaterialOrganization resolveQuoteOrganization(
      String processCode, String oaNo, String materialOrganizationCode, String contextOrganizationCode) {
    if (isPlateProcess(processCode) || isPlateProcess(oaNo)) {
      return PLATE;
    }
    MaterialOrganization explicitOrganization = fromBusinessUnitType(materialOrganizationCode);
    if (explicitOrganization != null) {
      return explicitOrganization;
    }
    MaterialOrganization contextOrganization = fromBusinessUnitType(contextOrganizationCode);
    if (contextOrganization != null) {
      return contextOrganization;
    }
    throw new IllegalArgumentException("非板换专用流程必须由报价明细显式传入料品组织");
  }

  private static MaterialOrganization fromBusinessUnitType(String businessUnitType) {
    return fromCodeOrNull(businessUnitType);
  }

  private static MaterialOrganization fromCodeOrNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    for (MaterialOrganization organization : values()) {
      if (organization.code.equalsIgnoreCase(normalized)
          || organization.label.equals(normalized)) {
        return organization;
      }
    }
    throw new IllegalArgumentException("料品组织仅支持 COMMERCIAL(商用) 或 PLATE(板换)");
  }

  private static MaterialOrganization fromPriceOrgCodeOrNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String normalized = value.trim();
    for (MaterialOrganization organization : values()) {
      if (organization.priceOrgCode.equals(normalized)) {
        return organization;
      }
    }
    throw new IllegalArgumentException("BOM报价组织仅支持 210(商用) 或 220(板换)");
  }

  private static boolean isPlateProcess(String process) {
    if (!StringUtils.hasText(process)) {
      return false;
    }
    String normalized = process.trim().toUpperCase(Locale.ROOT);
    String compact = normalized.replace("-", "").replace("_", "");
    return normalized.startsWith("FI-SC-020") || compact.startsWith("FISC020");
  }

}
