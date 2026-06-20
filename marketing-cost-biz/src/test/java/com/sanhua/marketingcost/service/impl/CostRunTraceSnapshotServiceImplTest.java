package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotBuilder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("成本核算底稿快照服务")
class CostRunTraceSnapshotServiceImplTest {

  @Test
  @DisplayName("重建底稿时先删除同一 costRunNo 再插入新快照")
  void rebuildDeletesSameCostRunNoBeforeInsert() {
    CostRunTraceSnapshotMapper mapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTraceSnapshotBuilder builder = mock(CostRunTraceSnapshotBuilder.class);
    CostRunTraceSnapshotServiceImpl service = new CostRunTraceSnapshotServiceImpl(mapper, builder);
    CostRunTraceSnapshot part = snapshot("RUN-T3", "PART_PRICE", "PART:1");
    CostRunTraceSnapshot total = snapshot("RUN-T3", "TOTAL", "TOTAL");
    QuoteCostRunVersion version = version("RUN-T3");
    when(builder.build(version)).thenReturn(List.of(part, total));

    int inserted = service.rebuildForVersion(version);

    assertThat(inserted).isEqualTo(2);
    verify(mapper).delete(any(Wrapper.class));
    ArgumentCaptor<CostRunTraceSnapshot> captor =
        ArgumentCaptor.forClass(CostRunTraceSnapshot.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(CostRunTraceSnapshot::getCostRunNo)
        .containsOnly("RUN-T3");
    assertThat(captor.getAllValues())
        .extracting(CostRunTraceSnapshot::getTraceKey)
        .containsExactly("PART:1", "TOTAL");
  }

  private QuoteCostRunVersion version(String costRunNo) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(1001L);
    version.setCostRunNo(costRunNo);
    return version;
  }

  private CostRunTraceSnapshot snapshot(String costRunNo, String traceType, String traceKey) {
    CostRunTraceSnapshot snapshot = new CostRunTraceSnapshot();
    snapshot.setCostRunNo(costRunNo);
    snapshot.setTraceType(traceType);
    snapshot.setTraceKey(traceKey);
    return snapshot;
  }
}
