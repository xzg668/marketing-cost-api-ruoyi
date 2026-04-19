package com.sanhua.marketingcost.dto;

import java.util.List;
import com.sanhua.marketingcost.entity.PriceRangeItem;

public class PriceRangeItemPageResponse {
  private long total;
  private List<PriceRangeItem> list;

  public PriceRangeItemPageResponse(long total, List<PriceRangeItem> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() { return total; }
  public void setTotal(long total) { this.total = total; }
  public List<PriceRangeItem> getList() { return list; }
  public void setList(List<PriceRangeItem> list) { this.list = list; }
}
