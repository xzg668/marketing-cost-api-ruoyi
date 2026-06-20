package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;

public interface CostRunTraceSnapshotService {

  int rebuildForVersion(QuoteCostRunVersion version);
}
