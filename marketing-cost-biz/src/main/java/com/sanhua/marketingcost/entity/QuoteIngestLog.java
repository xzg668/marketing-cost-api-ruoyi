package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("lp_quote_ingest_log")
public class QuoteIngestLog {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String requestId;
  private String idempotencyKey;
  private String payloadHash;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String oaNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private String ingestStatus;
  private String classificationStatus;
  private String payloadJson;
  private String normalizedJson;
  private String validationErrors;
  private String warningMessages;
  private String errorMessage;
  private LocalDateTime receivedAt;
  private LocalDateTime processedAt;
  private String createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
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

  public String getPayloadHash() {
    return payloadHash;
  }

  public void setPayloadHash(String payloadHash) {
    this.payloadHash = payloadHash;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceSystem() {
    return sourceSystem;
  }

  public void setSourceSystem(String sourceSystem) {
    this.sourceSystem = sourceSystem;
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

  public String getProcessName() {
    return processName;
  }

  public void setProcessName(String processName) {
    this.processName = processName;
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

  public String getPayloadJson() {
    return payloadJson;
  }

  public void setPayloadJson(String payloadJson) {
    this.payloadJson = payloadJson;
  }

  public String getNormalizedJson() {
    return normalizedJson;
  }

  public void setNormalizedJson(String normalizedJson) {
    this.normalizedJson = normalizedJson;
  }

  public String getValidationErrors() {
    return validationErrors;
  }

  public void setValidationErrors(String validationErrors) {
    this.validationErrors = validationErrors;
  }

  public String getWarningMessages() {
    return warningMessages;
  }

  public void setWarningMessages(String warningMessages) {
    this.warningMessages = warningMessages;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public LocalDateTime getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(LocalDateTime receivedAt) {
    this.receivedAt = receivedAt;
  }

  public LocalDateTime getProcessedAt() {
    return processedAt;
  }

  public void setProcessedAt(LocalDateTime processedAt) {
    this.processedAt = processedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
