package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class AuxRateItemRequest {
  private String materialCode;
  private String materialName;
  private String spec;
  private String model;
  private BigDecimal floatRate;
  private String period;
  private String source;

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getSpec() {
    return spec;
  }

  public void setSpec(String spec) {
    this.spec = spec;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public BigDecimal getFloatRate() {
    return floatRate;
  }

  public void setFloatRate(BigDecimal floatRate) {
    this.floatRate = floatRate;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
