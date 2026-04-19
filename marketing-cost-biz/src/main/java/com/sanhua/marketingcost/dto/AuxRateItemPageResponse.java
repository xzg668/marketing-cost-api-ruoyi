package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.AuxRateItem;
import java.util.List;

public class AuxRateItemPageResponse {
  private long total;
  private List<AuxRateItem> list;

  public AuxRateItemPageResponse(long total, List<AuxRateItem> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<AuxRateItem> getList() {
    return list;
  }

  public void setList(List<AuxRateItem> list) {
    this.list = list;
  }
}
