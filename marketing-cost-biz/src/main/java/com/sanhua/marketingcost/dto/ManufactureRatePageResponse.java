package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.ManufactureRate;
import java.util.List;

public class ManufactureRatePageResponse {
  private long total;
  private List<ManufactureRate> list;

  public ManufactureRatePageResponse(long total, List<ManufactureRate> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<ManufactureRate> getList() {
    return list;
  }

  public void setList(List<ManufactureRate> list) {
    this.list = list;
  }
}
