package com.sanhua.marketingcost.dto;

import java.util.List;

public class BomManualSummaryPageResponse {
  private long total;
  private List<BomManualSummaryRow> list;

  public BomManualSummaryPageResponse(long total, List<BomManualSummaryRow> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<BomManualSummaryRow> getList() {
    return list;
  }

  public void setList(List<BomManualSummaryRow> list) {
    this.list = list;
  }
}
