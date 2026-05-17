package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class LinkedFormulaRow {
  private String sourceSheetName;
  private Integer excelRowNumber;
  private String materialCode;
  private String linkedItemImportKey;
  private String formulaText;
  private String priceCellFormula;
  private BigDecimal priceCellValue;
  private String excelDerivedFormulaText;
  private Boolean hasFormula;

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public void setSourceSheetName(String sourceSheetName) {
    this.sourceSheetName = sourceSheetName;
  }

  public Integer getExcelRowNumber() {
    return excelRowNumber;
  }

  public void setExcelRowNumber(Integer excelRowNumber) {
    this.excelRowNumber = excelRowNumber;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getLinkedItemImportKey() {
    return linkedItemImportKey;
  }

  public void setLinkedItemImportKey(String linkedItemImportKey) {
    this.linkedItemImportKey = linkedItemImportKey;
  }

  public String getFormulaText() {
    return formulaText;
  }

  public void setFormulaText(String formulaText) {
    this.formulaText = formulaText;
  }

  public String getPriceCellFormula() {
    return priceCellFormula;
  }

  public void setPriceCellFormula(String priceCellFormula) {
    this.priceCellFormula = priceCellFormula;
  }

  public BigDecimal getPriceCellValue() {
    return priceCellValue;
  }

  public void setPriceCellValue(BigDecimal priceCellValue) {
    this.priceCellValue = priceCellValue;
  }

  public String getExcelDerivedFormulaText() {
    return excelDerivedFormulaText;
  }

  public void setExcelDerivedFormulaText(String excelDerivedFormulaText) {
    this.excelDerivedFormulaText = excelDerivedFormulaText;
  }

  public Boolean getHasFormula() {
    return hasFormula;
  }

  public void setHasFormula(Boolean hasFormula) {
    this.hasFormula = hasFormula;
  }
}
