package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageSnapshotResult {
  private PackageComponentSnapshot snapshot;
  private List<PackageComponentSnapshotDetail> details = new ArrayList<>();
  private boolean created;
  private String status;
  private List<String> warnings = new ArrayList<>();

  public static PackageSnapshotResult of(
      PackageComponentSnapshot snapshot,
      List<PackageComponentSnapshotDetail> details,
      boolean created) {
    PackageSnapshotResult result = new PackageSnapshotResult();
    result.setSnapshot(snapshot);
    result.setDetails(details == null ? new ArrayList<>() : new ArrayList<>(details));
    result.setCreated(created);
    result.setStatus(snapshot == null ? null : snapshot.getStatus());
    return result;
  }
}
