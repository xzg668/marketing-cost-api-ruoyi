package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteExcelImportPreviewResponse {
  private boolean valid;
  private String fileName;
  private int formCount;
  private int itemCount;
  private int feeCount;
  private List<QuoteIngestPreviewResponse> forms = new ArrayList<>();
  private List<QuoteValidationError> errors = new ArrayList<>();
  private List<QuoteValidationWarning> warnings = new ArrayList<>();

  public boolean isValid() {
    return valid;
  }

  public void setValid(boolean valid) {
    this.valid = valid;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public int getFormCount() {
    return formCount;
  }

  public void setFormCount(int formCount) {
    this.formCount = formCount;
  }

  public int getItemCount() {
    return itemCount;
  }

  public void setItemCount(int itemCount) {
    this.itemCount = itemCount;
  }

  public int getFeeCount() {
    return feeCount;
  }

  public void setFeeCount(int feeCount) {
    this.feeCount = feeCount;
  }

  public List<QuoteIngestPreviewResponse> getForms() {
    return forms;
  }

  public void setForms(List<QuoteIngestPreviewResponse> forms) {
    this.forms = forms;
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
