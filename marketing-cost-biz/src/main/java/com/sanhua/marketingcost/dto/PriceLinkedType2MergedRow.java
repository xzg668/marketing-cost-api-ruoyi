package com.sanhua.marketingcost.dto;

import java.util.List;

/** 一对一匹配后的类型 2 业务行，字段来源仍可回溯到两个原始 Sheet。 */
public final class PriceLinkedType2MergedRow {

  private final PriceLinkedType2ProductRow businessRow;
  private final PriceLinkedType2StandardRow standardRow;
  private final String pricingMonth;
  private final String businessUnit;
  private final String materialCode;
  private final String supplierName;
  private final String supplierCode;
  private final String source;
  private final String materialAttribute;
  private final String taxIncludedText;
  private final String effectiveDateText;
  private final String expiryDateText;
  private final String businessIdentityKey;
  private final boolean supplierFallback;

  public PriceLinkedType2MergedRow(
      PriceLinkedType2ProductRow businessRow,
      PriceLinkedType2StandardRow standardRow,
      String pricingMonth,
      String businessUnit,
      String materialCode,
      String supplierName,
      String supplierCode,
      String source,
      String materialAttribute,
      String taxIncludedText,
      String effectiveDateText,
      String expiryDateText,
      String businessIdentityKey) {
    this(
        businessRow,
        standardRow,
        pricingMonth,
        businessUnit,
        materialCode,
        supplierName,
        supplierCode,
        source,
        materialAttribute,
        taxIncludedText,
        effectiveDateText,
        expiryDateText,
        businessIdentityKey,
        false);
  }

  public PriceLinkedType2MergedRow(
      PriceLinkedType2ProductRow businessRow,
      PriceLinkedType2StandardRow standardRow,
      String pricingMonth,
      String businessUnit,
      String materialCode,
      String supplierName,
      String supplierCode,
      String source,
      String materialAttribute,
      String taxIncludedText,
      String effectiveDateText,
      String expiryDateText,
      String businessIdentityKey,
      boolean supplierFallback) {
    this.businessRow = businessRow;
    this.standardRow = standardRow;
    this.pricingMonth = pricingMonth;
    this.businessUnit = businessUnit;
    this.materialCode = materialCode;
    this.supplierName = supplierName;
    this.supplierCode = supplierCode;
    this.source = source;
    this.materialAttribute = materialAttribute;
    this.taxIncludedText = taxIncludedText;
    this.effectiveDateText = effectiveDateText;
    this.expiryDateText = expiryDateText;
    this.businessIdentityKey = businessIdentityKey;
    this.supplierFallback = supplierFallback;
  }

  public PriceLinkedType2ProductRow getBusinessRow() {
    return businessRow;
  }

  public PriceLinkedType2StandardRow getStandardRow() {
    return standardRow;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public String getSource() {
    return source;
  }

  public String getMaterialAttribute() {
    return materialAttribute;
  }

  public String getTaxIncludedText() {
    return taxIncludedText;
  }

  public String getEffectiveDateText() {
    return effectiveDateText;
  }

  public String getExpiryDateText() {
    return expiryDateText;
  }

  public String getBusinessIdentityKey() {
    return businessIdentityKey;
  }

  public boolean isSupplierFallback() {
    return supplierFallback;
  }

  public String getSourceFormula() {
    return businessRow.getTaxIncludedFormula();
  }

  public List<PriceLinkedType2CellSnapshot> getInputSnapshots() {
    return businessRow.getReferencedCells();
  }
}
