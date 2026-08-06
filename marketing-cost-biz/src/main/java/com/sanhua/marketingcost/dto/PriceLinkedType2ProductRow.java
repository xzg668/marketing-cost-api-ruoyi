package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

/** 类型 2 业务计算 Sheet 产品行及其公式来源快照。 */
public final class PriceLinkedType2ProductRow {

  private final String sourceSheetName;
  private final Integer sourceRowNumber;
  private final String materialCode;
  private final String productName;
  private final String specification;
  private final String unit;
  private final String supplierName;
  private final String taxIncludedFormula;
  private final String formulaCellRef;
  private final BigDecimal taxIncludedPrice;
  private final BigDecimal taxExcludedPrice;
  private final List<PriceLinkedType2CellSnapshot> referencedCells;

  public PriceLinkedType2ProductRow(
      String sourceSheetName,
      Integer sourceRowNumber,
      String materialCode,
      String productName,
      String specification,
      String unit,
      String supplierName,
      String taxIncludedFormula,
      String formulaCellRef,
      BigDecimal taxIncludedPrice,
      BigDecimal taxExcludedPrice,
      List<PriceLinkedType2CellSnapshot> referencedCells) {
    this.sourceSheetName = sourceSheetName;
    this.sourceRowNumber = sourceRowNumber;
    this.materialCode = materialCode;
    this.productName = productName;
    this.specification = specification;
    this.unit = unit;
    this.supplierName = supplierName;
    this.taxIncludedFormula = taxIncludedFormula;
    this.formulaCellRef = formulaCellRef;
    this.taxIncludedPrice = taxIncludedPrice;
    this.taxExcludedPrice = taxExcludedPrice;
    this.referencedCells = List.copyOf(referencedCells);
  }

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public String getProductName() {
    return productName;
  }

  public String getSpecification() {
    return specification;
  }

  public String getUnit() {
    return unit;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public String getTaxIncludedFormula() {
    return taxIncludedFormula;
  }

  public String getFormulaCellRef() {
    return formulaCellRef;
  }

  public BigDecimal getTaxIncludedPrice() {
    return taxIncludedPrice;
  }

  public BigDecimal getTaxExcludedPrice() {
    return taxExcludedPrice;
  }

  public List<PriceLinkedType2CellSnapshot> getReferencedCells() {
    return referencedCells;
  }
}
