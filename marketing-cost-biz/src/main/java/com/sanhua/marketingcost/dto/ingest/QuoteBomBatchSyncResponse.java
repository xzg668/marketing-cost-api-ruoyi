package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

/** 报价单产品行批量同步 BOM 响应。 */
public class QuoteBomBatchSyncResponse {
  private int selectedRowCount;
  private int distinctProductCount;
  private int syncedRowCount;
  private int noBomRowCount;
  private int skippedRowCount;
  private List<String> missingProductCodes = new ArrayList<>();
  private List<QuoteBomStatusItemResponse> items = new ArrayList<>();

  public int getSelectedRowCount() {
    return selectedRowCount;
  }

  public void setSelectedRowCount(int selectedRowCount) {
    this.selectedRowCount = selectedRowCount;
  }

  public int getDistinctProductCount() {
    return distinctProductCount;
  }

  public void setDistinctProductCount(int distinctProductCount) {
    this.distinctProductCount = distinctProductCount;
  }

  public int getSyncedRowCount() {
    return syncedRowCount;
  }

  public void setSyncedRowCount(int syncedRowCount) {
    this.syncedRowCount = syncedRowCount;
  }

  public int getNoBomRowCount() {
    return noBomRowCount;
  }

  public void setNoBomRowCount(int noBomRowCount) {
    this.noBomRowCount = noBomRowCount;
  }

  public int getSkippedRowCount() {
    return skippedRowCount;
  }

  public void setSkippedRowCount(int skippedRowCount) {
    this.skippedRowCount = skippedRowCount;
  }

  public List<String> getMissingProductCodes() {
    return missingProductCodes;
  }

  public void setMissingProductCodes(List<String> missingProductCodes) {
    this.missingProductCodes = missingProductCodes;
  }

  public List<QuoteBomStatusItemResponse> getItems() {
    return items;
  }

  public void setItems(List<QuoteBomStatusItemResponse> items) {
    this.items = items;
  }
}
