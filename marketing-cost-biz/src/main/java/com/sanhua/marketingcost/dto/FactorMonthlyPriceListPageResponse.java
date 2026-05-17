package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceListPageResponse {
  private long total;
  private List<FactorMonthlyPriceListRowDto> list;

  public FactorMonthlyPriceListPageResponse(long total, List<FactorMonthlyPriceListRowDto> list) {
    this.total = total;
    this.list = list;
  }
}
