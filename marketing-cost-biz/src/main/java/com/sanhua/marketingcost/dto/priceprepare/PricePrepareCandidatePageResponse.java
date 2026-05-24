package com.sanhua.marketingcost.dto.priceprepare;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareCandidatePageResponse {
  private long total;
  private List<PricePrepareCandidateResponse> records;

  public PricePrepareCandidatePageResponse() {}

  public PricePrepareCandidatePageResponse(
      long total, List<PricePrepareCandidateResponse> records) {
    this.total = total;
    this.records = records;
  }
}
