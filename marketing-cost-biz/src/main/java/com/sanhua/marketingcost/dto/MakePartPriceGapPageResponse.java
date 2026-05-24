package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartPriceGapPageResponse {
  private long total;
  private List<MakePartPriceGapItem> records;

  public MakePartPriceGapPageResponse() {
  }

  public MakePartPriceGapPageResponse(long total, List<MakePartPriceGapItem> records) {
    this.total = total;
    this.records = records;
  }
}
