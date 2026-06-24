package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmItem;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmItemMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuotePriceTypeConfirmationInvalidationServiceTest {

  private QuotePriceTypeConfirmBatchMapper batchMapper;
  private QuotePriceTypeConfirmItemMapper itemMapper;
  private QuoteCostRunVersionMapper costRunVersionMapper;
  private QuotePriceTypeConfirmationInvalidationService service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmBatch.class);
    TableInfoHelper.initTableInfo(assistant, QuotePriceTypeConfirmItem.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(QuotePriceTypeConfirmBatchMapper.class);
    itemMapper = mock(QuotePriceTypeConfirmItemMapper.class);
    costRunVersionMapper = mock(QuoteCostRunVersionMapper.class);
    service =
        new QuotePriceTypeConfirmationInvalidationService(
            batchMapper, itemMapper, costRunVersionMapper);
  }

  @Test
  void invalidateByMaterialPriceTypeChangesMarksCurrentConfirmBatchStale() {
    when(itemMapper.selectList(any())).thenReturn(List.of(item("PTC-001")));
    when(costRunVersionMapper.selectList(any())).thenReturn(List.of());
    when(batchMapper.update(isNull(), any())).thenReturn(1);

    int affected = service.invalidateByMaterialPriceTypeChanges(List.of(type("MAT-001")));

    assertThat(affected).isEqualTo(1);
    verify(batchMapper).update(isNull(), any());
  }

  @Test
  void invalidateByMaterialPriceTypeChangesSkipsConfirmedCostVersions() {
    when(itemMapper.selectList(any())).thenReturn(List.of(item("PTC-HISTORY")));
    when(costRunVersionMapper.selectList(any())).thenReturn(List.of(confirmedVersion("PTC-HISTORY")));

    int affected = service.invalidateByMaterialPriceTypeChanges(List.of(type("MAT-001")));

    assertThat(affected).isZero();
    verify(batchMapper, never()).update(isNull(), any());
  }

  @Test
  void invalidateScopeDoesNotUseMaterialItemLookup() {
    when(batchMapper.selectList(any())).thenReturn(List.of(batch("PTC-SCOPE")));
    when(costRunVersionMapper.selectList(any())).thenReturn(List.of());
    when(batchMapper.update(isNull(), any())).thenReturn(1);

    int affected = service.invalidateScope("OA-001", 10L, "FIN-001", "2026-06");

    assertThat(affected).isEqualTo(1);
    verify(itemMapper, never()).selectList(any());
  }

  private MaterialPriceType type(String materialCode) {
    MaterialPriceType type = new MaterialPriceType();
    type.setMaterialCode(materialCode);
    type.setPriceType("结算固定价");
    type.setPeriod("2026-06");
    type.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    type.setBusinessUnitType("COMMERCIAL");
    return type;
  }

  private QuotePriceTypeConfirmItem item(String confirmNo) {
    QuotePriceTypeConfirmItem item = new QuotePriceTypeConfirmItem();
    item.setConfirmNo(confirmNo);
    item.setMaterialCode("MAT-001");
    item.setPeriodMonth("2026-06");
    item.setStatus(QuotePriceTypeConfirmItem.STATUS_CONFIRMED);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuotePriceTypeConfirmBatch batch(String confirmNo) {
    QuotePriceTypeConfirmBatch batch = new QuotePriceTypeConfirmBatch();
    batch.setConfirmNo(confirmNo);
    batch.setStatus(QuotePriceTypeConfirmBatch.STATUS_CONFIRMED);
    return batch;
  }

  private QuoteCostRunVersion confirmedVersion(String confirmNo) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setPriceTypeConfirmNo(confirmNo);
    version.setStatus("CONFIRMED");
    return version;
  }
}
