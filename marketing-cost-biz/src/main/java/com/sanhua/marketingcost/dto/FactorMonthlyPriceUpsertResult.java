package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class FactorMonthlyPriceUpsertResult {
  private int identityCreatedCount;
  private int identityReusedCount;
  private int monthlyPriceCreatedCount;
  private int monthlyPriceUpdatedCount;
  private int monthlyPriceUnchangedCount;
  private int monthlyPriceSkippedCount;
  private int monthlyPriceConflictCount;
  private int monthlyPriceOverwriteCount;
  private int quoteBaseRecognizedCount;
  private int quoteBaseUnrecognizedCount;
  private int quoteBaseConflictCount;
  private final List<RowResult> rows = new ArrayList<>();
  private final List<RowError> errors = new ArrayList<>();

  public int getIdentityCreatedCount() {
    return identityCreatedCount;
  }

  public void setIdentityCreatedCount(int identityCreatedCount) {
    this.identityCreatedCount = identityCreatedCount;
  }

  public int getIdentityReusedCount() {
    return identityReusedCount;
  }

  public void setIdentityReusedCount(int identityReusedCount) {
    this.identityReusedCount = identityReusedCount;
  }

  public int getMonthlyPriceCreatedCount() {
    return monthlyPriceCreatedCount;
  }

  public void setMonthlyPriceCreatedCount(int monthlyPriceCreatedCount) {
    this.monthlyPriceCreatedCount = monthlyPriceCreatedCount;
  }

  public int getMonthlyPriceUpdatedCount() {
    return monthlyPriceUpdatedCount;
  }

  public void setMonthlyPriceUpdatedCount(int monthlyPriceUpdatedCount) {
    this.monthlyPriceUpdatedCount = monthlyPriceUpdatedCount;
  }

  public int getMonthlyPriceUnchangedCount() {
    return monthlyPriceUnchangedCount;
  }

  public void setMonthlyPriceUnchangedCount(int monthlyPriceUnchangedCount) {
    this.monthlyPriceUnchangedCount = monthlyPriceUnchangedCount;
  }

  public int getMonthlyPriceSkippedCount() {
    return monthlyPriceSkippedCount;
  }

  public void setMonthlyPriceSkippedCount(int monthlyPriceSkippedCount) {
    this.monthlyPriceSkippedCount = monthlyPriceSkippedCount;
  }

  public int getMonthlyPriceConflictCount() {
    return monthlyPriceConflictCount;
  }

  public void setMonthlyPriceConflictCount(int monthlyPriceConflictCount) {
    this.monthlyPriceConflictCount = monthlyPriceConflictCount;
  }

  public int getMonthlyPriceOverwriteCount() {
    return monthlyPriceOverwriteCount;
  }

  public void setMonthlyPriceOverwriteCount(int monthlyPriceOverwriteCount) {
    this.monthlyPriceOverwriteCount = monthlyPriceOverwriteCount;
  }

  public int getQuoteBaseRecognizedCount() {
    return quoteBaseRecognizedCount;
  }

  public void setQuoteBaseRecognizedCount(int quoteBaseRecognizedCount) {
    this.quoteBaseRecognizedCount = quoteBaseRecognizedCount;
  }

  public int getQuoteBaseUnrecognizedCount() {
    return quoteBaseUnrecognizedCount;
  }

  public void setQuoteBaseUnrecognizedCount(int quoteBaseUnrecognizedCount) {
    this.quoteBaseUnrecognizedCount = quoteBaseUnrecognizedCount;
  }

  public int getQuoteBaseConflictCount() {
    return quoteBaseConflictCount;
  }

  public void setQuoteBaseConflictCount(int quoteBaseConflictCount) {
    this.quoteBaseConflictCount = quoteBaseConflictCount;
  }

  public List<RowResult> getRows() {
    return rows;
  }

  public List<RowError> getErrors() {
    return errors;
  }

  public static class RowResult {
    private String sourceSheetName;
    private Integer sourceRowNumber;
    private Long factorIdentityId;
    private Long factorMonthlyPriceId;
    private String factorSeqNo;
    private String factorName;
    private String shortName;
    private String priceSource;
    private String identityAction;
    private String monthlyPriceAction;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private BigDecimal originalPrice;
    private String unit;
    private String quoteBaseDetectStatus;
    private String quoteBaseQuoteFieldCode;
    private String quoteBaseQuoteFieldName;
    private String quoteBaseVariableCode;
    private String quoteBaseMatchedKeyword;
    private String quoteBaseMatchSource;
    private String quoteBaseDetectMessage;
    private String uploadedBy;
    private LocalDateTime uploadedAt;

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

    public Long getFactorIdentityId() {
      return factorIdentityId;
    }

    public void setFactorIdentityId(Long factorIdentityId) {
      this.factorIdentityId = factorIdentityId;
    }

    public Long getFactorMonthlyPriceId() {
      return factorMonthlyPriceId;
    }

    public void setFactorMonthlyPriceId(Long factorMonthlyPriceId) {
      this.factorMonthlyPriceId = factorMonthlyPriceId;
    }

    public String getFactorSeqNo() {
      return factorSeqNo;
    }

    public void setFactorSeqNo(String factorSeqNo) {
      this.factorSeqNo = factorSeqNo;
    }

    public String getFactorName() {
      return factorName;
    }

    public void setFactorName(String factorName) {
      this.factorName = factorName;
    }

    public String getShortName() {
      return shortName;
    }

    public void setShortName(String shortName) {
      this.shortName = shortName;
    }

    public String getPriceSource() {
      return priceSource;
    }

    public void setPriceSource(String priceSource) {
      this.priceSource = priceSource;
    }

    public String getIdentityAction() {
      return identityAction;
    }

    public void setIdentityAction(String identityAction) {
      this.identityAction = identityAction;
    }

    public String getMonthlyPriceAction() {
      return monthlyPriceAction;
    }

    public void setMonthlyPriceAction(String monthlyPriceAction) {
      this.monthlyPriceAction = monthlyPriceAction;
    }

    public BigDecimal getOldPrice() {
      return oldPrice;
    }

    public void setOldPrice(BigDecimal oldPrice) {
      this.oldPrice = oldPrice;
    }

    public BigDecimal getNewPrice() {
      return newPrice;
    }

    public void setNewPrice(BigDecimal newPrice) {
      this.newPrice = newPrice;
    }

    public BigDecimal getOriginalPrice() {
      return originalPrice;
    }

    public void setOriginalPrice(BigDecimal originalPrice) {
      this.originalPrice = originalPrice;
    }

    public String getUnit() {
      return unit;
    }

    public void setUnit(String unit) {
      this.unit = unit;
    }

    public String getQuoteBaseDetectStatus() {
      return quoteBaseDetectStatus;
    }

    public void setQuoteBaseDetectStatus(String quoteBaseDetectStatus) {
      this.quoteBaseDetectStatus = quoteBaseDetectStatus;
    }

    public String getQuoteBaseQuoteFieldCode() {
      return quoteBaseQuoteFieldCode;
    }

    public void setQuoteBaseQuoteFieldCode(String quoteBaseQuoteFieldCode) {
      this.quoteBaseQuoteFieldCode = quoteBaseQuoteFieldCode;
    }

    public String getQuoteBaseQuoteFieldName() {
      return quoteBaseQuoteFieldName;
    }

    public void setQuoteBaseQuoteFieldName(String quoteBaseQuoteFieldName) {
      this.quoteBaseQuoteFieldName = quoteBaseQuoteFieldName;
    }

    public String getQuoteBaseVariableCode() {
      return quoteBaseVariableCode;
    }

    public void setQuoteBaseVariableCode(String quoteBaseVariableCode) {
      this.quoteBaseVariableCode = quoteBaseVariableCode;
    }

    public String getQuoteBaseMatchedKeyword() {
      return quoteBaseMatchedKeyword;
    }

    public void setQuoteBaseMatchedKeyword(String quoteBaseMatchedKeyword) {
      this.quoteBaseMatchedKeyword = quoteBaseMatchedKeyword;
    }

    public String getQuoteBaseMatchSource() {
      return quoteBaseMatchSource;
    }

    public void setQuoteBaseMatchSource(String quoteBaseMatchSource) {
      this.quoteBaseMatchSource = quoteBaseMatchSource;
    }

    public String getQuoteBaseDetectMessage() {
      return quoteBaseDetectMessage;
    }

    public void setQuoteBaseDetectMessage(String quoteBaseDetectMessage) {
      this.quoteBaseDetectMessage = quoteBaseDetectMessage;
    }

    public String getUploadedBy() {
      return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
      this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
      return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
      this.uploadedAt = uploadedAt;
    }
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
