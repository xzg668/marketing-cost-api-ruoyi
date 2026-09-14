package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataTaskSubmissionRequest {
  private Integer expectedTaskVersion;
  private String idempotencyKey;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Integer getExpectedTaskVersion() { return expectedTaskVersion; }
  public void setExpectedTaskVersion(Integer value) { expectedTaskVersion = value; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public void setIdempotencyKey(String value) { idempotencyKey = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
