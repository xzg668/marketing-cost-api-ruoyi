package com.sanhua.marketingcost.dto;

import java.util.List;
import com.sanhua.marketingcost.entity.PriceSettle;

public class PriceSettlePageResponse {
  private long total;
  private List<PriceSettle> list;

  public PriceSettlePageResponse(long total, List<PriceSettle> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() { return total; }
  public void setTotal(long total) { this.total = total; }
  public List<PriceSettle> getList() { return list; }
  public void setList(List<PriceSettle> list) { this.list = list; }
}
