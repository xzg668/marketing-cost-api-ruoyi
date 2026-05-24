package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.PricePrepareGap;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGapPageResponse {
  private long total;
  private List<PricePrepareGap> records;

  public PricePrepareGapPageResponse() {}

  public PricePrepareGapPageResponse(long total, List<PricePrepareGap> records) {
    this.total = total;
    this.records = records;
  }
}
