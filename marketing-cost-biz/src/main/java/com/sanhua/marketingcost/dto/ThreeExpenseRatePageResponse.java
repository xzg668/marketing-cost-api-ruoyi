package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import java.util.List;

public class ThreeExpenseRatePageResponse {
  private long total;
  private List<ThreeExpenseRate> list;

  public ThreeExpenseRatePageResponse(long total, List<ThreeExpenseRate> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<ThreeExpenseRate> getList() {
    return list;
  }

  public void setList(List<ThreeExpenseRate> list) {
    this.list = list;
  }
}
