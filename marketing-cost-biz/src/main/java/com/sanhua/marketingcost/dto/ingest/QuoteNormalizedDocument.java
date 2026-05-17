package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteNormalizedDocument {
  private QuoteClassificationResult classification;
  private QuoteNormalizedHeader header;
  private List<QuoteNormalizedItem> items = new ArrayList<>();
  private List<QuoteNormalizedExtraFee> extraFees = new ArrayList<>();
  private List<QuoteNormalizedExtraField> extraFields = new ArrayList<>();
  private List<QuoteValidationError> errors = new ArrayList<>();
  private List<QuoteValidationWarning> warnings = new ArrayList<>();

  public QuoteClassificationResult getClassification() {
    return classification;
  }

  public void setClassification(QuoteClassificationResult classification) {
    this.classification = classification;
  }

  public QuoteNormalizedHeader getHeader() {
    return header;
  }

  public void setHeader(QuoteNormalizedHeader header) {
    this.header = header;
  }

  public List<QuoteNormalizedItem> getItems() {
    return items;
  }

  public void setItems(List<QuoteNormalizedItem> items) {
    this.items = items;
  }

  public List<QuoteNormalizedExtraFee> getExtraFees() {
    return extraFees;
  }

  public void setExtraFees(List<QuoteNormalizedExtraFee> extraFees) {
    this.extraFees = extraFees;
  }

  public List<QuoteNormalizedExtraField> getExtraFields() {
    return extraFields;
  }

  public void setExtraFields(List<QuoteNormalizedExtraField> extraFields) {
    this.extraFields = extraFields;
  }

  public List<QuoteValidationError> getErrors() {
    return errors;
  }

  public void setErrors(List<QuoteValidationError> errors) {
    this.errors = errors;
  }

  public List<QuoteValidationWarning> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<QuoteValidationWarning> warnings) {
    this.warnings = warnings;
  }
}
