package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataPackageReferenceRequest {
  private Long sourceVersionId;
  private Integer expectedVersion;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Long getSourceVersionId() { return sourceVersionId; }
  public void setSourceVersionId(Long value) { sourceVersionId = value; }
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
