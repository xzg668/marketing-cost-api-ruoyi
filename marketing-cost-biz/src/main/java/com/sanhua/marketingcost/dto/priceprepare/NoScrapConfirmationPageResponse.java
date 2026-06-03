package com.sanhua.marketingcost.dto.priceprepare;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoScrapConfirmationPageResponse {
  private long total;
  private List<NoScrapConfirmResponse> records;

  public NoScrapConfirmationPageResponse() {}

  public NoScrapConfirmationPageResponse(long total, List<NoScrapConfirmResponse> records) {
    this.total = total;
    this.records = records;
  }
}
