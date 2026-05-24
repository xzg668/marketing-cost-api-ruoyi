package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SupplierSupplyRatioPageResponse {
  private long total;
  private List<SupplierSupplyRatio> records;

  public SupplierSupplyRatioPageResponse() {
  }

  public SupplierSupplyRatioPageResponse(long total, List<SupplierSupplyRatio> records) {
    this.total = total;
    this.records = records;
  }
}
