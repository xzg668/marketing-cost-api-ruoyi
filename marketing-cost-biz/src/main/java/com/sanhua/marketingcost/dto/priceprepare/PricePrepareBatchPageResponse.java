package com.sanhua.marketingcost.dto.priceprepare;

import com.sanhua.marketingcost.entity.PricePrepareBatch;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareBatchPageResponse {
  private long total;
  private List<PricePrepareBatch> records;

  public PricePrepareBatchPageResponse() {}

  public PricePrepareBatchPageResponse(long total, List<PricePrepareBatch> records) {
    this.total = total;
    this.records = records;
  }
}
