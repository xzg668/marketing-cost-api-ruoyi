package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataPackageItemRequest {
  private String componentMaterialNo;
  private String componentName;
  private String componentSpec;
  private BigDecimal quantity;
  private String unit;
  private String priceBasisType;
  private String remark;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getComponentMaterialNo() { return componentMaterialNo; }
  public void setComponentMaterialNo(String value) { componentMaterialNo = value; }
  public String getComponentName() { return componentName; }
  public void setComponentName(String value) { componentName = value; }
  public String getComponentSpec() { return componentSpec; }
  public void setComponentSpec(String value) { componentSpec = value; }
  public BigDecimal getQuantity() { return quantity; }
  public void setQuantity(BigDecimal value) { quantity = value; }
  public String getUnit() { return unit; }
  public void setUnit(String value) { unit = value; }
  public String getPriceBasisType() { return priceBasisType; }
  public void setPriceBasisType(String value) { priceBasisType = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { remark = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
