package com.sanhua.marketingcost.dto;

import java.util.Arrays;

/** 联动价 Excel 预检和确认分流的不可变请求。 */
public final class PriceLinkedImportCommand {

  private final byte[] fileBytes;
  private final String sourceFileName;
  private final String pricingMonth;
  private final String businessUnitType;
  private final boolean overwriteManual;
  private final String effectiveStrategy;
  private final String formulaEffectiveDate;
  private final String factorPriceConflictStrategy;
  private final String expectedPreviewSha256;

  public PriceLinkedImportCommand(
      byte[] fileBytes,
      String sourceFileName,
      String pricingMonth,
      String businessUnitType,
      boolean overwriteManual,
      String effectiveStrategy,
      String formulaEffectiveDate,
      String factorPriceConflictStrategy,
      String expectedPreviewSha256) {
    this.fileBytes = fileBytes == null ? new byte[0] : Arrays.copyOf(fileBytes, fileBytes.length);
    this.sourceFileName = sourceFileName;
    this.pricingMonth = pricingMonth;
    this.businessUnitType = businessUnitType;
    this.overwriteManual = overwriteManual;
    this.effectiveStrategy = effectiveStrategy;
    this.formulaEffectiveDate = formulaEffectiveDate;
    this.factorPriceConflictStrategy = factorPriceConflictStrategy;
    this.expectedPreviewSha256 = expectedPreviewSha256;
  }

  public byte[] getFileBytes() {
    return Arrays.copyOf(fileBytes, fileBytes.length);
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public boolean isOverwriteManual() {
    return overwriteManual;
  }

  public String getEffectiveStrategy() {
    return effectiveStrategy;
  }

  public String getFormulaEffectiveDate() {
    return formulaEffectiveDate;
  }

  public String getFactorPriceConflictStrategy() {
    return factorPriceConflictStrategy;
  }

  public String getExpectedPreviewSha256() {
    return expectedPreviewSha256;
  }
}
