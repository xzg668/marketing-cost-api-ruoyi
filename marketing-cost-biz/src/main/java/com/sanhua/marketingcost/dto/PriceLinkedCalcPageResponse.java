package com.sanhua.marketingcost.dto;

import java.util.List;

public class PriceLinkedCalcPageResponse {
  private long total;
  private List<PriceLinkedCalcRow> list;

  public PriceLinkedCalcPageResponse(long total, List<PriceLinkedCalcRow> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<PriceLinkedCalcRow> getList() {
    return list;
  }

  public void setList(List<PriceLinkedCalcRow> list) {
    this.list = list;
  }
}
