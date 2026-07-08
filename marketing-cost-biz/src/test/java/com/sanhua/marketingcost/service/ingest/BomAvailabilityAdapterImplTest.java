package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.time.LocalDate;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BomAvailabilityAdapterImplTest {
  private BomCostingRowMapper bomCostingRowMapper;
  private BomRawHierarchyMapper bomRawHierarchyMapper;
  private BomAvailabilityAdapterImpl adapter;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomRawHierarchy.class);
  }

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
    when(bomCostingRowMapper.selectAvailabilitySnapshot("OA-T7-001", "MAT-1001", "2026-06", "210"))
        .thenReturn(row);

    BomAvailability availability =
        adapter.findAvailableBom("OA-T7-001", "MAT-1001", "2026-06", "210");

    assertThat(availability.isAvailable()).isTrue();
    assertThat(availability.getSource()).isEqualTo("COSTING_SNAPSHOT");
    assertThat(availability.getSyncBatchId()).isEqualTo("costing-batch");
    verify(bomCostingRowMapper).selectAvailabilitySnapshot("OA-T7-001", "MAT-1001", "2026-06", "210");
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
    when(bomCostingRowMapper.selectAvailabilitySnapshot(
            "FI-SC-020-20260707-001", "MAT-1001", "2026-06", "220"))
        .thenReturn(null);
    when(bomRawHierarchyMapper.selectOne(any())).thenReturn(row);

    BomAvailability availability =
        adapter.findAvailableBom("FI-SC-020-20260707-001", "MAT-1001", "2026-06", "220");

    assertThat(availability.isAvailable()).isTrue();
    assertThat(availability.getSource()).isEqualTo("U9");
    assertThat(availability.getBomVersion()).isEqualTo("V2");
    assertRawHierarchyQueryUsesPriceOrg("220");
    verify(bomCostingRowMapper, never()).insert(any(BomCostingRow.class));
    verify(bomCostingRowMapper, never()).updateById(any(BomCostingRow.class));
    verify(bomRawHierarchyMapper, never()).insert(any(BomRawHierarchy.class));
    verify(bomRawHierarchyMapper, never()).updateById(any(BomRawHierarchy.class));
  }

  @Test
  void explicitPriceOrgRoutesRawHierarchyFallbackToPlateOrganization() {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setSourceType("U9");
    row.setBuildBatchId("raw-batch");
    when(bomCostingRowMapper.selectAvailabilitySnapshot("OA-T7-001", "MAT-PLATE", "2026-06", "220"))
        .thenReturn(null);
    when(bomRawHierarchyMapper.selectOne(any())).thenReturn(row);

    BomAvailability availability =
        adapter.findAvailableBom("OA-T7-001", "MAT-PLATE", "2026-06", "220");

    assertThat(availability.isAvailable()).isTrue();
    assertRawHierarchyQueryUsesPriceOrg("220");
  }

  @Test
  void missingPriceOrgFailsFast() {
    assertThatThrownBy(() -> adapter.findAvailableBom("OA-T7-001", "MAT-1001", "2026-06"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceOrgCode");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void assertRawHierarchyQueryUsesPriceOrg(String expectedPriceOrgCode) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(bomRawHierarchyMapper, atLeastOnce()).selectOne(captor.capture());
    Wrapper wrapper = captor.getValue();
    assertThat(wrapper.getSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs())
        .containsValue(expectedPriceOrgCode);
  }
}
