package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.util.List;

public interface CostRunTraceSnapshotBuilder {

  List<CostRunTraceSnapshot> build(QuoteCostRunVersion version);
}
