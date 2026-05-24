package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;

public interface PackageComponentSnapshotService {

  PackageSnapshotResult ensureSnapshot(PackageSnapshotRequest request);

  PackageSnapshotDetailResult getSnapshotDetail(Long snapshotId);
}
