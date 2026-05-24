package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ManufactureRate;
import java.util.ArrayList;
import java.util.List;

public class ManufactureRateImportResponse {
  private int inserted;
  private int updated;
  private int skipped;
  private int errors;
  private List<String> errorMessages = new ArrayList<>();
  private List<ManufactureRate> records = new ArrayList<>();

  public int getInserted() {
    return inserted;
  }

  public void setInserted(int inserted) {
    this.inserted = inserted;
  }

  public int getUpdated() {
    return updated;
  }

  public void setUpdated(int updated) {
    this.updated = updated;
  }

  public int getSkipped() {
    return skipped;
  }

  public void setSkipped(int skipped) {
    this.skipped = skipped;
  }

  public int getErrors() {
    return errors;
  }

  public void setErrors(int errors) {
    this.errors = errors;
  }

  public List<String> getErrorMessages() {
    return errorMessages;
  }

  public void setErrorMessages(List<String> errorMessages) {
    this.errorMessages = errorMessages == null ? new ArrayList<>() : errorMessages;
  }

  public List<ManufactureRate> getRecords() {
    return records;
  }

  public void setRecords(List<ManufactureRate> records) {
    this.records = records == null ? new ArrayList<>() : records;
  }

  public void incrementInserted() {
    this.inserted++;
  }

  public void incrementUpdated() {
    this.updated++;
  }

  public void incrementSkipped() {
    this.skipped++;
  }

  public void addError(String message) {
    this.errors++;
    this.errorMessages.add(message);
  }

  public void addRecord(ManufactureRate record) {
    if (record != null) {
      this.records.add(record);
    }
  }
}
