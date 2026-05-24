package com.sanhua.marketingcost.dto.packagecomponent;

import com.sanhua.marketingcost.entity.PackageComponentPrice;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentPricePageResponse {
  private long total;
  private List<PackageComponentPrice> records;

  public PackageComponentPricePageResponse() {}

  public PackageComponentPricePageResponse(long total, List<PackageComponentPrice> records) {
    this.total = total;
    this.records = records;
  }
}
