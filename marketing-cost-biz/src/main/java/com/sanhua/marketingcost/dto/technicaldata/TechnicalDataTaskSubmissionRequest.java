package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataTaskSubmissionRequest {
  private Long assigneeUserId;
  private Integer expectedTaskVersion;
  private Integer expectedVersion;
  private String idempotencyKey;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Long getAssigneeUserId() { return assigneeUserId; }
  public void setAssigneeUserId(Long value) { assigneeUserId = value; }
  public Integer getExpectedTaskVersion() { return expectedTaskVersion; }
  public void setExpectedTaskVersion(Integer value) { expectedTaskVersion = value; }
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String value) { idempotencyKey = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
