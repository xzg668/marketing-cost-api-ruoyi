package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class PriceLinkedImportResultClassifyRequest {
  private Integer excelRowNumber;
  private String materialCode;
  private String formula;
  private Boolean formulaAvailable;
  private final List<ResolvedFactorRef> resolvedRefs = new ArrayList<>();
  private final List<StandardBindingDecision> standardDecisions = new ArrayList<>();
  private PriceLinkedAutoBindingWriteResult writeResult;

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

  public String getFormula() {
    return formula;
  }

  public void setFormula(String formula) {
    this.formula = formula;
  }

  public Boolean getFormulaAvailable() {
    return formulaAvailable;
  }

  public void setFormulaAvailable(Boolean formulaAvailable) {
    this.formulaAvailable = formulaAvailable;
  }

  public List<ResolvedFactorRef> getResolvedRefs() {
    return resolvedRefs;
  }

  public List<StandardBindingDecision> getStandardDecisions() {
    return standardDecisions;
  }

  public PriceLinkedAutoBindingWriteResult getWriteResult() {
    return writeResult;
  }

  public void setWriteResult(PriceLinkedAutoBindingWriteResult writeResult) {
    this.writeResult = writeResult;
  }
}
