package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** GET /price-linked/items/{id}/import-basis 的返回对象。 */
public final class PriceLinkedImportBasisResponse {

  private Long linkedItemId;
  private boolean importBasisAvailable;
  private String message;
  private String pricingMonth;
  private String businessUnitType;
  private String materialCode;
  private String supplierCode;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private Long sourceUploadBatchId;
  private String sourceBatchNo;
  private String sourceFileName;
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private String sourceFormulaCellRef;
  private String sourceFormula;
  private String systemFormula;
  private Integer taxIncluded;
  private BigDecimal sourceTaxIncludedPrice;
  private BigDecimal sourceTaxExcludedPrice;
  private String sourceInputSnapshotJson;
  private PriceLinkedImportBasisSnapshot snapshot;
  private List<PriceLinkedImportBasisFactorResponse> factorBindings = List.of();

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public void setLinkedItemId(Long linkedItemId) {
    this.linkedItemId = linkedItemId;
  }

  public boolean isImportBasisAvailable() {
    return importBasisAvailable;
  }

  public void setImportBasisAvailable(boolean importBasisAvailable) {
    this.importBasisAvailable = importBasisAvailable;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public void setSupplierCode(String supplierCode) {
    this.supplierCode = supplierCode;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public Long getSourceUploadBatchId() {
    return sourceUploadBatchId;
  }

  public void setSourceUploadBatchId(Long sourceUploadBatchId) {
    this.sourceUploadBatchId = sourceUploadBatchId;
  }

  public String getSourceBatchNo() {
    return sourceBatchNo;
  }

  public void setSourceBatchNo(String sourceBatchNo) {
    this.sourceBatchNo = sourceBatchNo;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public void setSourceSheetName(String sourceSheetName) {
    this.sourceSheetName = sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public void setSourceRowNumber(Integer sourceRowNumber) {
    this.sourceRowNumber = sourceRowNumber;
  }

  public String getSourceFormulaCellRef() {
    return sourceFormulaCellRef;
  }

  public void setSourceFormulaCellRef(String sourceFormulaCellRef) {
    this.sourceFormulaCellRef = sourceFormulaCellRef;
  }

  public String getSourceFormula() {
    return sourceFormula;
  }

  public void setSourceFormula(String sourceFormula) {
    this.sourceFormula = sourceFormula;
  }

  public String getSystemFormula() {
    return systemFormula;
  }

  public void setSystemFormula(String systemFormula) {
    this.systemFormula = systemFormula;
  }

  public Integer getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Integer taxIncluded) {
    this.taxIncluded = taxIncluded;
  }

  public BigDecimal getSourceTaxIncludedPrice() {
    return sourceTaxIncludedPrice;
  }

  public void setSourceTaxIncludedPrice(BigDecimal sourceTaxIncludedPrice) {
    this.sourceTaxIncludedPrice = sourceTaxIncludedPrice;
  }

  public BigDecimal getSourceTaxExcludedPrice() {
    return sourceTaxExcludedPrice;
  }

  public void setSourceTaxExcludedPrice(BigDecimal sourceTaxExcludedPrice) {
    this.sourceTaxExcludedPrice = sourceTaxExcludedPrice;
  }

  public String getSourceInputSnapshotJson() {
    return sourceInputSnapshotJson;
  }

  public void setSourceInputSnapshotJson(String sourceInputSnapshotJson) {
    this.sourceInputSnapshotJson = sourceInputSnapshotJson;
  }

  public PriceLinkedImportBasisSnapshot getSnapshot() {
    return snapshot;
  }

  public void setSnapshot(PriceLinkedImportBasisSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  public List<PriceLinkedImportBasisFactorResponse> getFactorBindings() {
    return factorBindings;
  }

  public void setFactorBindings(List<PriceLinkedImportBasisFactorResponse> factorBindings) {
    this.factorBindings = List.copyOf(factorBindings);
  }
}
