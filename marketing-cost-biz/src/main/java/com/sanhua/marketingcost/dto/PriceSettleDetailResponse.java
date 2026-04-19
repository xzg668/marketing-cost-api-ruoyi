package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceSettle;
import com.sanhua.marketingcost.entity.PriceSettleItem;
import java.util.List;

public class PriceSettleDetailResponse {
  private PriceSettle settle;
  private List<PriceSettleItem> items;

  public PriceSettleDetailResponse(PriceSettle settle, List<PriceSettleItem> items) {
    this.settle = settle;
    this.items = items;
  }

  public PriceSettle getSettle() { return settle; }
  public void setSettle(PriceSettle settle) { this.settle = settle; }
  public List<PriceSettleItem> getItems() { return items; }
  public void setItems(List<PriceSettleItem> items) { this.items = items; }
}
