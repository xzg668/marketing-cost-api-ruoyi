package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageSnapshotDetailResult {
  private PackageComponentSnapshot snapshot;
  private List<PackageComponentSnapshotDetail> details = new ArrayList<>();
}
