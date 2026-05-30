package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepricePageResponse<T> {
  private long total;
  private List<T> list;

  public MonthlyRepricePageResponse(long total, List<T> list) {
    this.total = total;
    this.list = list;
  }
}
