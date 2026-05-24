package com.sanhua.marketingcost.dto.priceprepare;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareOaSummaryPageResponse {
  private long total;
  private List<PricePrepareOaSummaryResponse> records;

  public PricePrepareOaSummaryPageResponse() {}

  public PricePrepareOaSummaryPageResponse(long total, List<PricePrepareOaSummaryResponse> records) {
    this.total = total;
    this.records = records;
  }
}
