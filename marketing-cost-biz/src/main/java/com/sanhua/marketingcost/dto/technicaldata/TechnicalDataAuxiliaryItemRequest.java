package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataAuxiliaryItemRequest {
  private String subjectCode;
  private String subjectName;
  private String auxiliaryMaterialNo;
  private String auxiliaryName;
  private String auxiliarySpec;
  private String pricingMethod;
  private BigDecimal quantity;
  private String unit;
  private BigDecimal referenceUnitPrice;
  private String priceUnit;
  private BigDecimal lossRate;
  private String remark;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getSubjectCode() { return subjectCode; }
  public void setSubjectCode(String value) { subjectCode = value; }
  public String getSubjectName() { return subjectName; }
  public void setSubjectName(String value) { subjectName = value; }
  public String getAuxiliaryMaterialNo() { return auxiliaryMaterialNo; }
  public void setAuxiliaryMaterialNo(String value) { auxiliaryMaterialNo = value; }
  public String getAuxiliaryName() { return auxiliaryName; }
  public void setAuxiliaryName(String value) { auxiliaryName = value; }
  public String getAuxiliarySpec() { return auxiliarySpec; }
  public void setAuxiliarySpec(String value) { auxiliarySpec = value; }
  public String getPricingMethod() { return pricingMethod; }
  public void setPricingMethod(String value) { pricingMethod = value; }
  public BigDecimal getQuantity() { return quantity; }
  public void setQuantity(BigDecimal value) { quantity = value; }
  public String getUnit() { return unit; }
  public void setUnit(String value) { unit = value; }
  public BigDecimal getReferenceUnitPrice() { return referenceUnitPrice; }
  public void setReferenceUnitPrice(BigDecimal value) { referenceUnitPrice = value; }
  public String getPriceUnit() { return priceUnit; }
  public void setPriceUnit(String value) { priceUnit = value; }
  public BigDecimal getLossRate() { return lossRate; }
  public void setLossRate(BigDecimal value) { lossRate = value; }
  public String getRemark() { return remark; }
  public void setRemark(String value) { remark = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
