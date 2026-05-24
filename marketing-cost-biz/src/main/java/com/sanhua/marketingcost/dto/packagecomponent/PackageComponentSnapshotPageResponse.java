package com.sanhua.marketingcost.dto.packagecomponent;

import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentSnapshotPageResponse {
  private long total;
  private List<PackageComponentSnapshot> records;

  public PackageComponentSnapshotPageResponse() {}

  public PackageComponentSnapshotPageResponse(long total, List<PackageComponentSnapshot> records) {
    this.total = total;
    this.records = records;
  }
}
