package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TechnicalDataAuxiliarySaveRequest {
  private Integer expectedVersion;
  private List<TechnicalDataAuxiliaryItemRequest> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public List<TechnicalDataAuxiliaryItemRequest> getItems() { return items; }
  public void setItems(List<TechnicalDataAuxiliaryItemRequest> value) { items = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
