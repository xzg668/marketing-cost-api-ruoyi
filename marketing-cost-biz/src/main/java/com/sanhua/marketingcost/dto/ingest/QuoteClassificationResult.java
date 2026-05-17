package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteClassificationResult {
  private String businessUnitType;
  private String quoteScenario;
  private String classificationStatus;
  private String ruleCode;
  private int confidence;
  private List<QuoteValidationWarning> warnings = new ArrayList<>();

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public void setQuoteScenario(String quoteScenario) {
    this.quoteScenario = quoteScenario;
  }

  public String getClassificationStatus() {
    return classificationStatus;
  }

  public void setClassificationStatus(String classificationStatus) {
    this.classificationStatus = classificationStatus;
  }

  public String getRuleCode() {
    return ruleCode;
  }

  public void setRuleCode(String ruleCode) {
    this.ruleCode = ruleCode;
  }

  public int getConfidence() {
    return confidence;
  }

  public void setConfidence(int confidence) {
    this.confidence = confidence;
  }

  public List<QuoteValidationWarning> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<QuoteValidationWarning> warnings) {
    this.warnings = warnings;
  }
}
