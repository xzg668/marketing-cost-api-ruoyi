package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.PricePrepareItem;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareItemPageResponse {
  private long total;
  private List<PricePrepareItem> records;

  public PricePrepareItemPageResponse() {}

  public PricePrepareItemPageResponse(long total, List<PricePrepareItem> records) {
    this.total = total;
    this.records = records;
  }
}
