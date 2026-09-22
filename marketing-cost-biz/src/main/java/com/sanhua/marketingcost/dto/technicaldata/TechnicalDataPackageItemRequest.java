package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataPackageItemRequest {
  private Long sourceParentNodeId;
  private Long sourceNodeId;
  private String sourceFingerprint;
  private String componentModel;
  private String componentName;
  private String componentSpec;
  private BigDecimal quantity;
  private String unit;
  private String remark;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Long getSourceParentNodeId() { return sourceParentNodeId; }
  public void setSourceParentNodeId(Long value) { sourceParentNodeId = value; }
  public Long getSourceNodeId() { return sourceNodeId; }
  public void setSourceNodeId(Long value) { sourceNodeId = value; }
  public String getSourceFingerprint() { return sourceFingerprint; }
  public void setSourceFingerprint(String value) { sourceFingerprint = value; }
  public String getComponentModel() { return componentModel; }
  public void setComponentModel(String value) { componentModel = value; }
  public String getComponentName() { return componentName; }
  public void setComponentName(String value) { componentName = value; }
  public String getComponentSpec() { return componentSpec; }
  public void setComponentSpec(String value) { componentSpec = value; }
  public BigDecimal getQuantity() { return quantity; }
  public void setQuantity(BigDecimal value) { quantity = value; }
  public String getUnit() { return unit; }
  public void setUnit(String value) { unit = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { remark = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
