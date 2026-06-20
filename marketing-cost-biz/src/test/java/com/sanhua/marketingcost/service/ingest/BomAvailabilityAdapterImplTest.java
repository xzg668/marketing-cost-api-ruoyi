package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BomAvailabilityAdapterImplTest {
  private BomCostingRowMapper bomCostingRowMapper;
  private BomRawHierarchyMapper bomRawHierarchyMapper;
  private BomAvailabilityAdapterImpl adapter;

  @BeforeEach
  void setUp() {
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    bomRawHierarchyMapper = mock(BomRawHierarchyMapper.class);
    adapter = new BomAvailabilityAdapterImpl(bomCostingRowMapper, bomRawHierarchyMapper);
  }

  @Test
  void findsCurrentQuoteSnapshotFirst() {
    BomCostingRow row = new BomCostingRow();
    row.setBomPurpose("10");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    row.setBuildBatchId("costing-batch");
    when(bomCostingRowMapper.selectAvailabilitySnapshot("MAT-1001", "2026-06")).thenReturn(row);

    BomAvailability availability = adapter.findAvailableBom("OA-T7-001", "MAT-1001", "2026-06");

    assertThat(availability.isAvailable()).isTrue();
    assertThat(availability.getSource()).isEqualTo("COSTING_SNAPSHOT");
    assertThat(availability.getSyncBatchId()).isEqualTo("costing-batch");
    verify(bomRawHierarchyMapper, never()).selectOne(any());
  }

  @Test
  void fallsBackToRawHierarchyAndDoesNotModifyBomData() {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setSourceType("U9");
    row.setBomPurpose("10");
    row.setBomVersion("V2");
    row.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    row.setBuildBatchId("raw-batch");
    when(bomCostingRowMapper.selectAvailabilitySnapshot("MAT-1001", "2026-06")).thenReturn(null);
    when(bomRawHierarchyMapper.selectOne(any())).thenReturn(row);

    BomAvailability availability = adapter.findAvailableBom("OA-T7-001", "MAT-1001", "2026-06");

    assertThat(availability.isAvailable()).isTrue();
    assertThat(availability.getSource()).isEqualTo("U9");
    assertThat(availability.getBomVersion()).isEqualTo("V2");
    verify(bomCostingRowMapper, never()).insert(any(BomCostingRow.class));
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
    verify(bomRawHierarchyMapper, never()).insert(any(BomRawHierarchy.class));
    verify(bomRawHierarchyMapper, never()).updateById(any(BomRawHierarchy.class));
  }
}
