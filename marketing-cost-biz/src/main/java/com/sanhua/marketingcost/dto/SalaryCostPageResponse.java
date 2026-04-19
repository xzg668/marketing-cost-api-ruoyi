package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.SalaryCost;
import java.util.List;

public class SalaryCostPageResponse {
  private long total;
  private List<SalaryCost> list;

  public SalaryCostPageResponse(long total, List<SalaryCost> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<SalaryCost> getList() {
    return list;
  }

  public void setList(List<SalaryCost> list) {
    this.list = list;
  }
}
