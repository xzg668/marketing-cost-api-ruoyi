package com.sanhua.marketingcost.dto;

import java.util.List;

public class BomManageParentPageResponse {
  private long total;
  private List<BomManageParentRow> list;

  public BomManageParentPageResponse(long total, List<BomManageParentRow> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<BomManageParentRow> getList() {
    return list;
  }

  public void setList(List<BomManageParentRow> list) {
    this.list = list;
  }
}
