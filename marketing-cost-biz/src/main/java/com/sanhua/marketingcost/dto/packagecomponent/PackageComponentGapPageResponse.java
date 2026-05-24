package com.sanhua.marketingcost.dto.packagecomponent;

import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentGapPageResponse {
  private long total;
  private List<PackageComponentGapItem> records;

  public PackageComponentGapPageResponse() {}

  public PackageComponentGapPageResponse(long total, List<PackageComponentGapItem> records) {
    this.total = total;
    this.records = records;
  }
}
