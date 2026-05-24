package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class OaProductPropertyUsageSyncRequest {
  private Integer quoteYear;
  private String oaNo;
  private String businessUnitType;
  private List<OaProductPropertyUsageSyncRow> items = new ArrayList<>();

  public Integer getQuoteYear() {
    return quoteYear;
  }

  public void setQuoteYear(Integer quoteYear) {
    this.quoteYear = quoteYear;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public List<OaProductPropertyUsageSyncRow> getItems() {
    return items;
  }

  public void setItems(List<OaProductPropertyUsageSyncRow> items) {
    this.items = items == null ? new ArrayList<>() : items;
  }
}
