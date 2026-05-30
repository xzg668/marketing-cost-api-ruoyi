package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import java.util.ArrayList;
import java.util.List;

public class ThreeExpenseRateImportResponse {
  private int totalCount;
  private int insertedCount;
  private int updatedCount;
  private int failedCount;
  private int duplicateOverrideCount;
  private List<String> messages = new ArrayList<>();
  private List<String> errors = new ArrayList<>();
  private List<ThreeExpenseRate> rows = new ArrayList<>();

  public int getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(int totalCount) {
    this.totalCount = totalCount;
  }

  public int getInsertedCount() {
    return insertedCount;
  }

  public void setInsertedCount(int insertedCount) {
    this.insertedCount = insertedCount;
  }

  public int getUpdatedCount() {
    return updatedCount;
  }

  public void setUpdatedCount(int updatedCount) {
    this.updatedCount = updatedCount;
  }

  public int getFailedCount() {
    return failedCount;
  }

  public void setFailedCount(int failedCount) {
    this.failedCount = failedCount;
  }

  public int getDuplicateOverrideCount() {
    return duplicateOverrideCount;
  }

  public void setDuplicateOverrideCount(int duplicateOverrideCount) {
    this.duplicateOverrideCount = duplicateOverrideCount;
  }

  public List<String> getMessages() {
    return messages;
  }

  public void setMessages(List<String> messages) {
    this.messages = messages;
  }

  public List<String> getErrors() {
    return errors;
  }

  public void setErrors(List<String> errors) {
    this.errors = errors;
  }

  public List<ThreeExpenseRate> getRows() {
    return rows;
  }

  public void setRows(List<ThreeExpenseRate> rows) {
    this.rows = rows;
  }

  public void addMessage(String message) {
    this.messages.add(message);
  }

  public void addError(String error) {
    this.errors.add(error);
    this.failedCount = this.errors.size();
  }
}
