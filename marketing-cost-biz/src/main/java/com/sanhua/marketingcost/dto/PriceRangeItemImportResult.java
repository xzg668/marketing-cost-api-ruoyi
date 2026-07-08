package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceRangeItem;
import java.util.List;

public class PriceRangeItemImportResult {
  private List<PriceRangeItem> items;
  private List<RangePriceTypeConflict> priceTypeConflicts;

  public PriceRangeItemImportResult() {
  }

  public PriceRangeItemImportResult(
      List<PriceRangeItem> items,
      List<RangePriceTypeConflict> priceTypeConflicts) {
    this.items = items;
    this.priceTypeConflicts = priceTypeConflicts;
  }

  public List<PriceRangeItem> getItems() {
    return items;
  }

  public void setItems(List<PriceRangeItem> items) {
    this.items = items;
  }

  public List<RangePriceTypeConflict> getPriceTypeConflicts() {
    return priceTypeConflicts;
  }

  public void setPriceTypeConflicts(List<RangePriceTypeConflict> priceTypeConflicts) {
    this.priceTypeConflicts = priceTypeConflicts;
  }
}
