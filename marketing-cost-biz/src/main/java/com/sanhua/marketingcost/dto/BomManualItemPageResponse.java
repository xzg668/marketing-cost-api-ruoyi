package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.BomManualItem;
import java.util.List;

public class BomManualItemPageResponse {
  private long total;
  private List<BomManualItem> list;

  public BomManualItemPageResponse(long total, List<BomManualItem> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<BomManualItem> getList() {
    return list;
  }

  public void setList(List<BomManualItem> list) {
    this.list = list;
  }
}
