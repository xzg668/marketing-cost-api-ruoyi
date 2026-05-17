package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class QuoteIngestRequest {
  private String requestId;
  private String idempotencyKey;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String oaNo;
  private String version;
  private QuoteIngestHeaderRequest header;
  private List<QuoteIngestItemRequest> items = new ArrayList<>();
  private List<QuoteExtraFieldRequest> extraFields = new ArrayList<>();
  private List<QuoteExtraFeeRequest> extraFees = new ArrayList<>();
  private Map<String, Object> rawPayload;

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

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public QuoteIngestHeaderRequest getHeader() {
    return header;
  }

  public void setHeader(QuoteIngestHeaderRequest header) {
    this.header = header;
  }

  public List<QuoteIngestItemRequest> getItems() {
    return items;
  }

  public void setItems(List<QuoteIngestItemRequest> items) {
    this.items = items;
  }

  public List<QuoteExtraFieldRequest> getExtraFields() {
    return extraFields;
  }

  public void setExtraFields(List<QuoteExtraFieldRequest> extraFields) {
    this.extraFields = extraFields;
  }

  public List<QuoteExtraFeeRequest> getExtraFees() {
    return extraFees;
  }

  public void setExtraFees(List<QuoteExtraFeeRequest> extraFees) {
    this.extraFees = extraFees;
  }

  public Map<String, Object> getRawPayload() {
    return rawPayload;
  }

  public void setRawPayload(Map<String, Object> rawPayload) {
    this.rawPayload = rawPayload;
  }
}
