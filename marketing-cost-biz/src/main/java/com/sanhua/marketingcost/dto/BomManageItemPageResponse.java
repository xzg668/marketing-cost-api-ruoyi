package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.BomManageItem;
import java.util.List;

public class BomManageItemPageResponse {
  private long total;
  private List<BomManageItem> list;

  public BomManageItemPageResponse(long total, List<BomManageItem> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<BomManageItem> getList() {
    return list;
  }

  public void setList(List<BomManageItem> list) {
    this.list = list;
  }
}
