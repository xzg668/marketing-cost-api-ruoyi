package com.sanhua.marketingcost.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustBatchPageResponse {
  private long total;
  private List<FactorAdjustBatchDto> list;

  public FactorAdjustBatchPageResponse(long total, List<FactorAdjustBatchDto> list) {
    this.total = total;
    this.list = list;
  }
}
