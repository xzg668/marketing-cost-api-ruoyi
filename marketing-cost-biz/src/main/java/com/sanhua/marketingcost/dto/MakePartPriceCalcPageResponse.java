package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartPriceCalcPageResponse {
  private long total;
  private List<MakePartPriceCalcRow> records;

  public MakePartPriceCalcPageResponse() {
  }

  public MakePartPriceCalcPageResponse(long total, List<MakePartPriceCalcRow> records) {
    this.total = total;
    this.records = records;
  }
}
