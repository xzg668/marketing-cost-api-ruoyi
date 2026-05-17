package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class FactorRowRefSaveResult {
  private Long factorUploadBatchId;
  private int insertedCount;
  private int updatedCount;
  private int skippedCount;
  private final List<RowError> errors = new ArrayList<>();

  public Long getFactorUploadBatchId() {
    return factorUploadBatchId;
  }

  public void setFactorUploadBatchId(Long factorUploadBatchId) {
    this.factorUploadBatchId = factorUploadBatchId;
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

  public int getSkippedCount() {
    return skippedCount;
  }

  public void setSkippedCount(int skippedCount) {
    this.skippedCount = skippedCount;
  }

  public List<RowError> getErrors() {
    return errors;
  }

  public int getSavedCount() {
    return insertedCount + updatedCount;
  }

  public static class RowError {
    private String sourceSheetName;
    private Integer sourceRowNumber;
    private String message;

    public RowError() {
    }

    public RowError(String sourceSheetName, Integer sourceRowNumber, String message) {
      this.sourceSheetName = sourceSheetName;
      this.sourceRowNumber = sourceRowNumber;
      this.message = message;
    }

    public String getSourceSheetName() {
      return sourceSheetName;
    }

    public void setSourceSheetName(String sourceSheetName) {
      this.sourceSheetName = sourceSheetName;
    }

    public Integer getSourceRowNumber() {
      return sourceRowNumber;
    }

    public void setSourceRowNumber(Integer sourceRowNumber) {
      this.sourceRowNumber = sourceRowNumber;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }
}
