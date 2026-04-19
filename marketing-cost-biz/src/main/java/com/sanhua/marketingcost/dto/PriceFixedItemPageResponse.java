package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceFixedItem;
import java.util.List;

public class PriceFixedItemPageResponse {
  private long total;
  private List<PriceFixedItem> list;

  public PriceFixedItemPageResponse(long total, List<PriceFixedItem> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<PriceFixedItem> getList() {
    return list;
  }

  public void setList(List<PriceFixedItem> list) {
    this.list = list;
  }
}
