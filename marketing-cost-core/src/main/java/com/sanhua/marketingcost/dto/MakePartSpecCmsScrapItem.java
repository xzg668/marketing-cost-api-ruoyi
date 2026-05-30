package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 自制件规格页展示用的 CMS 回收废料映射和当前价状态。 */
public class MakePartSpecCmsScrapItem {
  private String scrapCode;
  private String scrapName;
  private String scrapUnit;
  private BigDecimal mappingRatio;
  private BigDecimal currentRecyclePrice;
  private String currentPriceUnit;
  private String currentPriceMonth;
  private String status;

  public String getScrapCode() {
    return scrapCode;
  }

  public void setScrapCode(String scrapCode) {
    this.scrapCode = scrapCode;
  }

  public String getScrapName() {
    return scrapName;
  }

  public void setScrapName(String scrapName) {
    this.scrapName = scrapName;
  }

  public String getScrapUnit() {
    return scrapUnit;
  }

  public void setScrapUnit(String scrapUnit) {
    this.scrapUnit = scrapUnit;
  }

  public BigDecimal getMappingRatio() {
    return mappingRatio;
  }

  public void setMappingRatio(BigDecimal mappingRatio) {
    this.mappingRatio = mappingRatio;
  }

  public BigDecimal getCurrentRecyclePrice() {
    return currentRecyclePrice;
  }

  public void setCurrentRecyclePrice(BigDecimal currentRecyclePrice) {
    this.currentRecyclePrice = currentRecyclePrice;
  }

  public String getCurrentPriceUnit() {
    return currentPriceUnit;
  }

  public void setCurrentPriceUnit(String currentPriceUnit) {
    this.currentPriceUnit = currentPriceUnit;
  }

  public String getCurrentPriceMonth() {
    return currentPriceMonth;
  }

  public void setCurrentPriceMonth(String currentPriceMonth) {
    this.currentPriceMonth = currentPriceMonth;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }
}
