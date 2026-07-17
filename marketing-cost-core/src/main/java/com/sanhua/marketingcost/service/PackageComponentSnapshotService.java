package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PackageSnapshotDetailResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;

public interface PackageComponentSnapshotService {

  PackageSnapshotResult ensureSnapshot(PackageSnapshotRequest request);

  /** 只读取并临时组装包装结构，不创建快照、明细或缺口记录。 */
  PackageSnapshotResult previewSnapshot(PackageSnapshotRequest request);

  PackageSnapshotDetailResult getSnapshotDetail(Long snapshotId);
}
