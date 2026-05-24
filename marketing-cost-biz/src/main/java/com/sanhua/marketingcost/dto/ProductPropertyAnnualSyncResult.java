package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ProductProperty;
import java.util.ArrayList;
import java.util.List;

public class ProductPropertyAnnualSyncResult {
  private int inserted;
  private int updated;
  private int skipped;
  private int errors;
  private int risks;
  private List<ProductProperty> records = new ArrayList<>();
  private List<String> errorMessages = new ArrayList<>();
  private List<String> warnings = new ArrayList<>();

  public int getInserted() {
    return inserted;
  }

  public void incrementInserted() {
    this.inserted++;
  }

  public int getUpdated() {
    return updated;
  }

  public void incrementUpdated() {
    this.updated++;
  }

  public int getSkipped() {
    return skipped;
  }

  public void incrementSkipped() {
    this.skipped++;
  }

  public int getErrors() {
    return errors;
  }

  public void incrementErrors() {
    this.errors++;
  }

  public int getRisks() {
    return risks;
  }

  public void incrementRisks() {
    this.risks++;
  }

  public List<ProductProperty> getRecords() {
    return records;
  }

  public void setRecords(List<ProductProperty> records) {
    this.records = records == null ? new ArrayList<>() : records;
  }

  public List<String> getErrorMessages() {
    return errorMessages;
  }

  public void setErrorMessages(List<String> errorMessages) {
    this.errorMessages = errorMessages == null ? new ArrayList<>() : errorMessages;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<String> warnings) {
    this.warnings = warnings == null ? new ArrayList<>() : warnings;
  }

  public void addRecord(ProductProperty record) {
    if (record != null) {
      this.records.add(record);
    }
  }

  public void addError(String message) {
    incrementErrors();
    this.errorMessages.add(message);
  }

  public void addWarning(String message) {
    this.warnings.add(message);
  }
}
