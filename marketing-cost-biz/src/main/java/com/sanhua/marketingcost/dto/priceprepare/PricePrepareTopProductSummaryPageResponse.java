package com.sanhua.marketingcost.dto.priceprepare;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareTopProductSummaryPageResponse {
  private long total;
  private List<PricePrepareTopProductSummaryResponse> records;

  public PricePrepareTopProductSummaryPageResponse() {}

  public PricePrepareTopProductSummaryPageResponse(
      long total, List<PricePrepareTopProductSummaryResponse> records) {
    this.total = total;
    this.records = records;
  }
}
