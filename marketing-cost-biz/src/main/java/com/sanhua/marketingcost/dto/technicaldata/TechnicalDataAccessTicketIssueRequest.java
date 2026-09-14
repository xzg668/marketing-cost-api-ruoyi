package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataAccessTicketIssueRequest {
  private Long userId;
  private String purpose;
  private Integer ttlSeconds;
  private String reason;
  private String requestId;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Long getUserId() { return userId; }
  public void setUserId(Long value) { userId = value; }
  public String getPurpose() { return purpose; }
  public void setPurpose(String value) { purpose = value; }
  public Integer getTtlSeconds() { return ttlSeconds; }
  public void setTtlSeconds(Integer value) { ttlSeconds = value; }
  public String getReason() { return reason; }
  public void setReason(String value) { reason = value; }
  public String getRequestId() { return requestId; }
  public void setRequestId(String value) { requestId = value; }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
  @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }
}
