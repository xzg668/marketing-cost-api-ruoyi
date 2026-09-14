package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataAuxiliaryReferenceRequest {
  private String sourceType;
  private String sourceId;
  private Integer expectedVersion;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getSourceType() { return sourceType; }
  public void setSourceType(String value) { sourceType = value; }
  public String getSourceId() { return sourceId; }
  public void setSourceId(String value) { sourceId = value; }
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
