package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteBomStatusResponse {
  private String oaNo;
  private int totalCount;
  private int syncedCount;
  private int noBomCount;
  private int uncheckedCount;
  private List<QuoteBomStatusItemResponse> items = new ArrayList<>();

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public int getTotalCount() {
    return totalCount;
  }

  public void setTotalCount(int totalCount) {
    this.totalCount = totalCount;
  }

  public int getSyncedCount() {
    return syncedCount;
  }

  public void setSyncedCount(int syncedCount) {
    this.syncedCount = syncedCount;
  }

  public int getNoBomCount() {
    return noBomCount;
  }

  public void setNoBomCount(int noBomCount) {
    this.noBomCount = noBomCount;
  }

  public int getUncheckedCount() {
    return uncheckedCount;
  }

  public void setUncheckedCount(int uncheckedCount) {
    this.uncheckedCount = uncheckedCount;
  }

  public List<QuoteBomStatusItemResponse> getItems() {
    return items;
  }

  public void setItems(List<QuoteBomStatusItemResponse> items) {
    this.items = items;
  }
}
