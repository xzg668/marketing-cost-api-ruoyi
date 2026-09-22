package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅接收技术确认的属性与单件费用；OA 来源字段不接受回写。 */
public final class TechnicalDataProfileUpdateRequest {
  private String productProperty;
  private Boolean hasAdditionalFees;
  private String unitToolingFee;
  private String unitMouldFee;
  private String unitCertificationFee;
  private Integer expectedVersion;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getProductProperty() { return productProperty; }
  public void setProductProperty(String value) { productProperty = value; }
  public Boolean getHasAdditionalFees() { return hasAdditionalFees; }
  public void setHasAdditionalFees(Boolean value) { hasAdditionalFees = value; }
  public String getUnitToolingFee() { return unitToolingFee; }
  public void setUnitToolingFee(String value) { unitToolingFee = value; }
  public String getUnitMouldFee() { return unitMouldFee; }
  public void setUnitMouldFee(String value) { unitMouldFee = value; }
  public String getUnitCertificationFee() { return unitCertificationFee; }
  public void setUnitCertificationFee(String value) { unitCertificationFee = value; }
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }

  @JsonAnySetter
  public void captureUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return Collections.unmodifiableMap(unknownFields); }
}
