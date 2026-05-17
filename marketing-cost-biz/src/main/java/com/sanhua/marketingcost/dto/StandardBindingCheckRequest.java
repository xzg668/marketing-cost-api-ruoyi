package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class StandardBindingCheckRequest {
  private String businessUnitType;
  private String materialCode;
  private String supplierCode;
  private String linkedItemImportKey;
  private Long factorUploadBatchId;
  private String formulaText;
  private Boolean formulaAvailable;
  private String operator;
  private final List<BindingCandidate> candidates = new ArrayList<>();

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

  public String getLinkedItemImportKey() {
    return linkedItemImportKey;
  }

  public void setLinkedItemImportKey(String linkedItemImportKey) {
    this.linkedItemImportKey = linkedItemImportKey;
  }

  public Long getFactorUploadBatchId() {
    return factorUploadBatchId;
  }

  public void setFactorUploadBatchId(Long factorUploadBatchId) {
    this.factorUploadBatchId = factorUploadBatchId;
  }

  public String getFormulaText() {
    return formulaText;
  }

  public void setFormulaText(String formulaText) {
    this.formulaText = formulaText;
  }

  public Boolean getFormulaAvailable() {
    return formulaAvailable;
  }

  public void setFormulaAvailable(Boolean formulaAvailable) {
    this.formulaAvailable = formulaAvailable;
  }

  public String getOperator() {
    return operator;
  }

  public void setOperator(String operator) {
    this.operator = operator;
  }

  public List<BindingCandidate> getCandidates() {
    return candidates;
  }
}
