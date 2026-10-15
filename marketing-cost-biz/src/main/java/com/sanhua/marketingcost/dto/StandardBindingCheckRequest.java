package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class StandardBindingCheckRequest {
  private Long linkedItemId;
  private String organizationCode;
  private String pricingMonth;
  private String unit;
  private String specModel;

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public void setLinkedItemId(Long value) {
    linkedItemId = value;
  }

  public String getOrganizationCode() {
    return organizationCode;
  }

  public void setOrganizationCode(String value) {
    organizationCode = value;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String value) {
    pricingMonth = value;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String value) {
    unit = value;
  }

  public String getSpecModel() {
    return specModel;
  }

  public void setSpecModel(String value) {
    specModel = value;
  }

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
