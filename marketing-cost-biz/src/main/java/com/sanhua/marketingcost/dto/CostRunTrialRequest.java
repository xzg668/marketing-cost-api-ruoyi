package com.sanhua.marketingcost.dto;

import java.util.List;

public class CostRunTrialRequest {
  private String oaNo;
  private List<Long> oaFormItemIds;

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public List<Long> getOaFormItemIds() {
    return oaFormItemIds;
  }

  public void setOaFormItemIds(List<Long> oaFormItemIds) {
    this.oaFormItemIds = oaFormItemIds;
  }
}
