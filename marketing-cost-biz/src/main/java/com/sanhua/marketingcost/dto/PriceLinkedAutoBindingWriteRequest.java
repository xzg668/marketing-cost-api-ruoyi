package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class PriceLinkedAutoBindingWriteRequest {
  private Long linkedItemId;
  private String pricingMonth;
  private Long factorUploadBatchId;
  private String excelFormula;
  private Boolean overwriteManualBinding;
  private final List<StandardBindingDecision> decisions = new ArrayList<>();

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public void setLinkedItemId(Long linkedItemId) {
    this.linkedItemId = linkedItemId;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
  }

  public Long getFactorUploadBatchId() {
    return factorUploadBatchId;
  }

  public void setFactorUploadBatchId(Long factorUploadBatchId) {
    this.factorUploadBatchId = factorUploadBatchId;
  }

  public String getExcelFormula() {
    return excelFormula;
  }

  public void setExcelFormula(String excelFormula) {
    this.excelFormula = excelFormula;
  }

  public Boolean getOverwriteManualBinding() {
    return overwriteManualBinding;
  }

  public void setOverwriteManualBinding(Boolean overwriteManualBinding) {
    this.overwriteManualBinding = overwriteManualBinding;
  }

  public List<StandardBindingDecision> getDecisions() {
    return decisions;
  }
}
