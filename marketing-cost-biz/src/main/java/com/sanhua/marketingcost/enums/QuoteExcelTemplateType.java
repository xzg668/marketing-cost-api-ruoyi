package com.sanhua.marketingcost.enums;

import java.util.Locale;

/** 报价单 OA 原始表单 Excel 模板类型。 */
public enum QuoteExcelTemplateType {
  FI_SC_020(
      "FI-SC-020",
      "FI-SC-020",
      QuoteScenario.DIRECT_SALE.getCode(),
      "板换科技直销",
      "报价单导入模板_01_FI-SC-020_板换科技直销.xlsx",
      "quote-ingest/templates/quote-template-fi-sc-020.xlsx"),
  FI_SC_006(
      "FI-SC-006",
      "FI-SC-006",
      QuoteScenario.STANDARD_BATCH.getCode(),
      "标准品批量品",
      "报价单导入模板_02_FI-SC-006_标准品批量品.xlsx",
      "quote-ingest/templates/quote-template-fi-sc-006.xlsx"),
  FI_SC_005(
      "FI-SC-005",
      "FI-SC-005",
      QuoteScenario.NEW_PRODUCT.getCode(),
      "新品",
      "报价单导入模板_03_FI-SC-005_新品.xlsx",
      "quote-ingest/templates/quote-template-fi-sc-005.xlsx"),
  FI_SR_005_NEW(
      "FI-SR-005_NEW",
      "FI-SR-005",
      QuoteScenario.NEW_PRODUCT.getCode(),
      "家代商新品",
      "报价单导入模板_04_FI-SR-005_家代商新品.xlsx",
      "quote-ingest/templates/quote-template-fi-sr-005-new.xlsx"),
  FI_SR_005_MASS(
      "FI-SR-005_MASS",
      "FI-SR-005",
      QuoteScenario.MASS_PRODUCT.getCode(),
      "家代商批量品",
      "报价单导入模板_05_FI-SR-005_家代商批量品.xlsx",
      "quote-ingest/templates/quote-template-fi-sr-005-mass.xlsx"),
  FI_SR_005_DERIVED(
      "FI-SR-005_DERIVED",
      "FI-SR-005",
      QuoteScenario.DERIVED_PRODUCT.getCode(),
      "家代商衍生品",
      "报价单导入模板_06_FI-SR-005_家代商衍生品.xlsx",
      "quote-ingest/templates/quote-template-fi-sr-005-derived.xlsx");

  private final String code;
  private final String processCode;
  private final String quoteScenario;
  private final String displayName;
  private final String fileName;
  private final String resourcePath;

  QuoteExcelTemplateType(
      String code,
      String processCode,
      String quoteScenario,
      String displayName,
      String fileName,
      String resourcePath) {
    this.code = code;
    this.processCode = processCode;
    this.quoteScenario = quoteScenario;
    this.displayName = displayName;
    this.fileName = fileName;
    this.resourcePath = resourcePath;
  }

  public String getCode() {
    return code;
  }

  public String getProcessCode() {
    return processCode;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getFileName() {
    return fileName;
  }

  public String getResourcePath() {
    return resourcePath;
  }

  public static QuoteExcelTemplateType fromCode(String code) {
    String normalized = normalize(code);
    for (QuoteExcelTemplateType value : values()) {
      if (value.code.equalsIgnoreCase(normalized) || value.name().equals(normalized.replace("-", "_"))) {
        return value;
      }
    }
    throw new IllegalArgumentException("未知报价单模板类型: " + code);
  }

  private static String normalize(String code) {
    if (code == null) {
      return "";
    }
    return code.trim().toUpperCase(Locale.ROOT);
  }
}
