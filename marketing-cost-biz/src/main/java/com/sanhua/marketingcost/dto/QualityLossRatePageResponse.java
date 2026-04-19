package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.QualityLossRate;
import java.util.List;

public class QualityLossRatePageResponse {
  private long total;
  private List<QualityLossRate> list;

  public QualityLossRatePageResponse(long total, List<QualityLossRate> list) {
    this.total = total;
    this.list = list;
  }

  public long getTotal() {
    return total;
  }

  public void setTotal(long total) {
    this.total = total;
  }

  public List<QualityLossRate> getList() {
    return list;
  }

  public void setList(List<QualityLossRate> list) {
    this.list = list;
  }
}
