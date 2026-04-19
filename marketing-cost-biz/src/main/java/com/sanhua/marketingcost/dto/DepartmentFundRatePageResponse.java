package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.DepartmentFundRate;
import java.util.List;

public class DepartmentFundRatePageResponse {
  private long total;
  private List<DepartmentFundRate> list;

  public DepartmentFundRatePageResponse(long total, List<DepartmentFundRate> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<DepartmentFundRate> getList() {
    return list;
  }

  public void setList(List<DepartmentFundRate> list) {
    this.list = list;
  }
}
