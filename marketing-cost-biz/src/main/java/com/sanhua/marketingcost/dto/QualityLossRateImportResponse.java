package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class QualityLossRateImportResponse {
  private int inserted;
  private int updated;
  private int skipped;
  private int errors;
  private String sourceBatchNo;
  private List<String> errorMessages = new ArrayList<>();

  public int getInserted() { return inserted; }
  public void setInserted(int value) { this.inserted = value; }
  public int getUpdated() { return updated; }
  public void setUpdated(int value) { this.updated = value; }
  public int getSkipped() { return skipped; }
  public void setSkipped(int value) { this.skipped = value; }
  public int getErrors() { return errors; }
  public void setErrors(int value) { this.errors = value; }
  public String getSourceBatchNo() { return sourceBatchNo; }
  public void setSourceBatchNo(String value) { this.sourceBatchNo = value; }
  public List<String> getErrorMessages() { return errorMessages; }
  public void setErrorMessages(List<String> value) {
    this.errorMessages = value == null ? new ArrayList<>() : value;
  }
  public void incrementInserted() { inserted++; }
  public void incrementUpdated() { updated++; }
  public void incrementSkipped() { skipped++; }
  public void addError(String message) {
    errors++;
    if (errorMessages.size() < 20) {
      errorMessages.add(message);
    }
  }
}
