package com.sanhua.marketingcost.dto.ingest;

import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import java.util.ArrayList;
import java.util.List;

public class QuoteIngestResponse {
  private boolean accepted;
  private Long ingestLogId;
  private Long oaFormId;
  private String requestId;
  private String idempotencyKey;
  private String sourceType;
  private String externalFormNo;
  private String oaNo;
  private String processCode;
  private String quoteScenario;
  private String ingestStatus;
  private String classificationStatus;
  private int itemCount;
  private ProductPropertyAnnualSyncResult annualUsageSyncResult;
  private List<QuoteValidationError> errors = new ArrayList<>();
  private List<QuoteValidationWarning> warnings = new ArrayList<>();

  public boolean isAccepted() {
    return accepted;
  }

  public void setAccepted(boolean accepted) {
    this.accepted = accepted;
  }

  public Long getIngestLogId() {
    return ingestLogId;
  }

  public void setIngestLogId(Long ingestLogId) {
    this.ingestLogId = ingestLogId;
  }

  public Long getOaFormId() {
    return oaFormId;
  }

  public void setOaFormId(Long oaFormId) {
    this.oaFormId = oaFormId;
  }

  public String getRequestId() {
    return requestId;
  }

  public void setRequestId(String requestId) {
    this.requestId = requestId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getExternalFormNo() {
    return externalFormNo;
  }

  public void setExternalFormNo(String externalFormNo) {
    this.externalFormNo = externalFormNo;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getProcessCode() {
    return processCode;
  }

  public void setProcessCode(String processCode) {
    this.processCode = processCode;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public void setQuoteScenario(String quoteScenario) {
    this.quoteScenario = quoteScenario;
  }

  public String getIngestStatus() {
    return ingestStatus;
  }

  public void setIngestStatus(String ingestStatus) {
    this.ingestStatus = ingestStatus;
  }

  public String getClassificationStatus() {
    return classificationStatus;
  }

  public void setClassificationStatus(String classificationStatus) {
    this.classificationStatus = classificationStatus;
  }

  public int getItemCount() {
    return itemCount;
  }

  public void setItemCount(int itemCount) {
    this.itemCount = itemCount;
  }

  public ProductPropertyAnnualSyncResult getAnnualUsageSyncResult() {
    return annualUsageSyncResult;
  }

  public void setAnnualUsageSyncResult(ProductPropertyAnnualSyncResult annualUsageSyncResult) {
    this.annualUsageSyncResult = annualUsageSyncResult;
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
