package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TechnicalDataPackageSaveRequest {
  private Integer expectedVersion;
  private String entryMode;
  private java.math.BigDecimal parentQuantity;
  private Long referenceParentNodeId;
  private String referenceFingerprint;
  private List<TechnicalDataPackageItemRequest> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public java.math.BigDecimal getParentQuantity() { return parentQuantity; }
  public void setParentQuantity(java.math.BigDecimal value) { parentQuantity = value; }
  public Long getReferenceParentNodeId() { return referenceParentNodeId; }
  public void setReferenceParentNodeId(Long value) { referenceParentNodeId = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public List<TechnicalDataPackageItemRequest> getItems() { return items; }
  public void setItems(List<TechnicalDataPackageItemRequest> value) { items = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
