package com.sanhua.marketingcost.dto;

import java.time.LocalDate;

public class RangePriceTypeConflict {
  private String materialCode;
  private String materialName;
  private String businessUnitType;
  private String currentPriceType;
  private String suggestedPriceType;
  private String conflictType;
  private String period;
  private LocalDate effectiveFrom;
  private String message;

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

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getCurrentPriceType() {
    return currentPriceType;
  }

  public void setCurrentPriceType(String currentPriceType) {
    this.currentPriceType = currentPriceType;
  }

  public String getSuggestedPriceType() {
    return suggestedPriceType;
  }

  public void setSuggestedPriceType(String suggestedPriceType) {
    this.suggestedPriceType = suggestedPriceType;
  }

  public String getConflictType() {
    return conflictType;
  }

  public void setConflictType(String conflictType) {
    this.conflictType = conflictType;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
