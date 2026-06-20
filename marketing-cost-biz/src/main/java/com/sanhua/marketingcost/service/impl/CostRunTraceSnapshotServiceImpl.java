package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotBuilder;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CostRunTraceSnapshotServiceImpl implements CostRunTraceSnapshotService {

  private final CostRunTraceSnapshotMapper traceSnapshotMapper;
  private final CostRunTraceSnapshotBuilder snapshotBuilder;

  public CostRunTraceSnapshotServiceImpl(
      CostRunTraceSnapshotMapper traceSnapshotMapper,
      CostRunTraceSnapshotBuilder snapshotBuilder) {
    this.traceSnapshotMapper = traceSnapshotMapper;
    this.snapshotBuilder = snapshotBuilder;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int rebuildForVersion(QuoteCostRunVersion version) {
    if (version == null || !StringUtils.hasText(version.getCostRunNo())) {
      return 0;
    }
    String costRunNo = version.getCostRunNo().trim();
    traceSnapshotMapper.delete(
        Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
            .eq(CostRunTraceSnapshot::getCostRunNo, costRunNo));
    List<CostRunTraceSnapshot> snapshots = snapshotBuilder.build(version);
    int inserted = 0;
    for (CostRunTraceSnapshot snapshot : snapshots) {
      if (snapshot == null) {
        continue;
      }
      traceSnapshotMapper.insert(snapshot);
      inserted++;
    }
    return inserted;
  }
}
