package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.CmsCostDeriveLog;
import java.util.List;

public class CmsCostDeriveLogPageResponse {
  private long total;
  private List<CmsCostDeriveLog> list;

  public CmsCostDeriveLogPageResponse(long total, List<CmsCostDeriveLog> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<CmsCostDeriveLog> getList() {
    return list;
  }

  public void setList(List<CmsCostDeriveLog> list) {
    this.list = list;
  }
}
