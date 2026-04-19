package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.OtherExpenseRate;
import java.util.List;

public class OtherExpenseRatePageResponse {
  private long total;
  private List<OtherExpenseRate> list;

  public OtherExpenseRatePageResponse(long total, List<OtherExpenseRate> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<OtherExpenseRate> getList() {
    return list;
  }

  public void setList(List<OtherExpenseRate> list) {
    this.list = list;
  }
}
