package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostRunTraceQueryServiceImplTest {

  @Test
  void listByCostRunNoMapsHeaderAndRecordsThroughDataScopedSelectList() {
    CostRunTraceSnapshotMapper mapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTraceQueryServiceImpl service = new CostRunTraceQueryServiceImpl(mapper);
    CostRunTraceSnapshot part = snapshot(11L, "RUN-8", "PART_PRICE", "PART:1");
    part.setMaterialCode("MAT-1");
    part.setMaterialName("不锈钢棒");
    part.setUnitPrice(new BigDecimal("27.256637"));
    CostRunTraceSnapshot total = snapshot(12L, "RUN-8", "TOTAL", "TOTAL");
    total.setCostCode("TOTAL");
    total.setCostName("不含税总成本");
    total.setAmount(new BigDecimal("88.123456"));
    when(mapper.selectList(any())).thenReturn(List.of(part, total));

    CostRunTraceListResponse response = service.listByCostRunNo(" RUN-8 ");

    assertThat(response.getCostRunNo()).isEqualTo("RUN-8");
    assertThat(response.getCostRunVersionId()).isEqualTo(1001L);
    assertThat(response.getOaNo()).isEqualTo("OA-1");
    assertThat(response.getProductCode()).isEqualTo("P-1");
    assertThat(response.getPricingMonth()).isEqualTo("2026-06");
    assertThat(response.getTotal()).isEqualTo(2);
    assertThat(response.getRecords())
        .extracting(com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListItemDto::getId)
        .containsExactly(11L, 12L);
    assertThat(response.getRecords().get(0).getMaterialName()).isEqualTo("不锈钢棒");
    assertThat(response.getRecords().get(1).getAmount()).isEqualByComparingTo("88.123456");
    verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  void detailRequiresTraceIdToBelongToCostRunNoAndMapsJsonFields() {
    CostRunTraceSnapshotMapper mapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTraceQueryServiceImpl service = new CostRunTraceQueryServiceImpl(mapper);
    CostRunTraceSnapshot row = snapshot(22L, "RUN-8", "PART_PRICE", "PART:2");
    row.setSourceSnapshotJson("{\"source\":\"MAKE_PART\"}");
    row.setFormulaSnapshotJson("{\"formula\":\"grossWeight\"}");
    row.setVariablesJson("{\"grossWeightG\":5.1}");
    row.setStepsJson("[{\"code\":\"RAW_MATERIAL_AMOUNT\"}]");
    row.setChildrenJson("[{\"childMaterialNo\":\"301220018\"}]");
    when(mapper.selectList(any())).thenReturn(List.of(row));

    CostRunTraceDetailDto detail = service.getByCostRunNoAndId("RUN-8", 22L);

    assertThat(detail.getId()).isEqualTo(22L);
    assertThat(detail.getCostRunNo()).isEqualTo("RUN-8");
    assertThat(detail.getTraceKey()).isEqualTo("PART:2");
    assertThat(detail.getSourceSnapshotJson()).contains("MAKE_PART");
    assertThat(detail.getChildrenJson()).contains("301220018");
    ArgumentCaptor<Wrapper<CostRunTraceSnapshot>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue()).isNotNull();
  }

  @Test
  void detailReturnsNullWhenTraceDoesNotBelongToCostRunNoOrIsFilteredByDataScope() {
    CostRunTraceSnapshotMapper mapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTraceQueryServiceImpl service = new CostRunTraceQueryServiceImpl(mapper);
    when(mapper.selectList(any())).thenReturn(List.of());

    assertThat(service.getByCostRunNoAndId("RUN-8", 22L)).isNull();
    verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  void rejectsBlankCostRunNo() {
    CostRunTraceSnapshotMapper mapper = mock(CostRunTraceSnapshotMapper.class);
    CostRunTraceQueryServiceImpl service = new CostRunTraceQueryServiceImpl(mapper);

    assertThatThrownBy(() -> service.listByCostRunNo(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("costRunNo");
  }

  private CostRunTraceSnapshot snapshot(Long id, String costRunNo, String traceType, String traceKey) {
    CostRunTraceSnapshot snapshot = new CostRunTraceSnapshot();
    snapshot.setId(id);
    snapshot.setCostRunVersionId(1001L);
    snapshot.setCostRunNo(costRunNo);
    snapshot.setVersionNo("V1");
    snapshot.setOaNo("OA-1");
    snapshot.setOaFormItemId(101L);
    snapshot.setProductCode("P-1");
    snapshot.setPricingMonth("2026-06");
    snapshot.setTraceType(traceType);
    snapshot.setTraceKey(traceKey);
    snapshot.setBusinessUnitType("COMMERCIAL");
    snapshot.setCreatedAt(LocalDateTime.of(2026, 6, 18, 10, 0));
    snapshot.setUpdatedAt(LocalDateTime.of(2026, 6, 18, 10, 1));
    return snapshot;
  }
}
