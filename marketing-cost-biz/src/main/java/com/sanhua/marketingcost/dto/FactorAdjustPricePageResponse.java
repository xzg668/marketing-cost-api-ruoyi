package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustPricePageResponse {
  private long total;
  private List<FactorAdjustPriceDto> list;

  public FactorAdjustPricePageResponse(long total, List<FactorAdjustPriceDto> list) {
    this.total = total;
    this.list = list;
  }
}
