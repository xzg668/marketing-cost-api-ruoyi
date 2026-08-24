package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.MakePartPriceGapItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceGapItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareCurrentStateServiceImplTest {

  private PricePrepareBatchMapper batchMapper;
  private PricePrepareItemMapper itemMapper;
  private PricePrepareGapMapper gapMapper;
  private QuoteCostRunVersionMapper versionMapper;
  private PackageComponentPriceMapper packagePriceMapper;
  private PackageComponentPriceDetailMapper packageDetailMapper;
  private MakePartPriceCalcRowMapper makePartRowMapper;
  private MakePartPriceGapItemMapper makePartGapMapper;
  private PriceLinkedCalcItemMapper linkedCalcItemMapper;
  private QuoteCostingWorkspaceService workspaceService;
  private QuoteCostRunVersionInvalidationService invalidationService;
  private PricePrepareCurrentStateServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareGap.class);
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentPrice.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentPriceDetail.class);
    TableInfoHelper.initTableInfo(assistant, MakePartPriceCalcRow.class);
    TableInfoHelper.initTableInfo(assistant, MakePartPriceGapItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(PricePrepareBatchMapper.class);
    itemMapper = mock(PricePrepareItemMapper.class);
    gapMapper = mock(PricePrepareGapMapper.class);
    versionMapper = mock(QuoteCostRunVersionMapper.class);
    packagePriceMapper = mock(PackageComponentPriceMapper.class);
    packageDetailMapper = mock(PackageComponentPriceDetailMapper.class);
    makePartRowMapper = mock(MakePartPriceCalcRowMapper.class);
    makePartGapMapper = mock(MakePartPriceGapItemMapper.class);
    linkedCalcItemMapper = mock(PriceLinkedCalcItemMapper.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    invalidationService = mock(QuoteCostRunVersionInvalidationService.class);
    service =
        new PricePrepareCurrentStateServiceImpl(
            batchMapper,
            itemMapper,
            gapMapper,
            versionMapper,
            packagePriceMapper,
            packageDetailMapper,
            makePartRowMapper,
            makePartGapMapper,
            linkedCalcItemMapper,
            workspaceService,
            invalidationService);
    when(batchMapper.selectList(any())).thenReturn(List.of());
    when(itemMapper.selectList(any())).thenReturn(List.of());
    when(packagePriceMapper.selectList(any())).thenReturn(List.of());
    when(makePartRowMapper.selectList(any())).thenReturn(List.of());
  }

  @Test
  @DisplayName("成功候选原子切为当前最终价格并只在此时作废旧试算")
  void successfulCandidatePromotesWorkspace() {
    PricePrepareBatch batch = batch(20L, "PPR-NEW", "SUCCESS", 0);
    QuoteCostingWorkspace workspace = workspace("PPR-OLD");
    when(workspaceService.lockOrCreate(any(), any(), any(), any(), any()))
        .thenReturn(workspace);

    service.finalizeBatch(batch);

    ArgumentCaptor<QuoteCostingWorkspace> workspaceCaptor =
        ArgumentCaptor.forClass(QuoteCostingWorkspace.class);
    verify(workspaceService).update(workspaceCaptor.capture(), org.mockito.ArgumentMatchers.eq(0));
    QuoteCostingWorkspace published = workspaceCaptor.getValue();
    assertThat(published.getCurrentPrepareNo()).isEqualTo("PPR-NEW");
    assertThat(published.getWorkspaceStatus()).isEqualTo("PRICE_READY");
    assertThat(published.getCurrentStep()).isEqualTo("COST_RUN");
    assertThat(published.getGapCount()).isZero();
    verify(itemMapper, times(2)).update(any(), any());
    verify(invalidationService)
        .invalidateProduct("OA-1", 101L, "TOP-1", "2026-08");
  }

  @Test
  @DisplayName("失败候选不切换旧成功指针，只保留本次缺口摘要")
  void failedCandidateKeepsPreviousPointer() {
    PricePrepareBatch batch = batch(21L, "PPR-FAILED", "PARTIAL", 3);
    QuoteCostingWorkspace workspace = workspace("PPR-OLD");
    when(workspaceService.lockOrCreate(any(), any(), any(), any(), any()))
        .thenReturn(workspace);
    PricePrepareItem makeItem = new PricePrepareItem();
    makeItem.setItemType("MAKE_PART");
    makeItem.setMaterialCode("MAKE-1");
    MakePartPriceCalcRow makeRow = new MakePartPriceCalcRow();
    makeRow.setCalcBatchId("MPPG-1");
    when(itemMapper.selectList(any())).thenReturn(List.of(makeItem));
    when(makePartRowMapper.selectList(any())).thenReturn(List.of(makeRow));

    service.finalizeBatch(batch);

    ArgumentCaptor<QuoteCostingWorkspace> workspaceCaptor =
        ArgumentCaptor.forClass(QuoteCostingWorkspace.class);
    verify(workspaceService).update(workspaceCaptor.capture(), org.mockito.ArgumentMatchers.eq(0));
    QuoteCostingWorkspace blocked = workspaceCaptor.getValue();
    assertThat(blocked.getCurrentPrepareNo()).isEqualTo("PPR-OLD");
    assertThat(blocked.getWorkspaceStatus()).isEqualTo("PRICE_BLOCKED");
    assertThat(blocked.getGapCount()).isEqualTo(3);
    verify(itemMapper).delete(any());
    verify(makePartGapMapper).delete(any());
    verify(makePartRowMapper).delete(any());
    verify(gapMapper, times(2)).update(any(), any());
    verify(invalidationService, never()).invalidateProduct(any(), any(), any(), any());
  }

  @Test
  @DisplayName("重复生成清理未引用旧临时批次")
  void deletesOldUnreferencedTemporaryBatch() {
    PricePrepareBatch current = batch(30L, "PPR-NEW", "SUCCESS", 0);
    PricePrepareBatch old = batch(10L, "PPR-TEMP", "PARTIAL", 2);
    QuoteCostingWorkspace workspace = workspace("PPR-OLD");
    when(workspaceService.lockOrCreate(any(), any(), any(), any(), any()))
        .thenReturn(workspace);
    when(batchMapper.selectList(any())).thenReturn(List.of(old));
    when(versionMapper.selectList(any())).thenReturn(List.of());

    service.finalizeBatch(current);

    verify(batchMapper).delete(any());
    verify(itemMapper).delete(any());
    verify(gapMapper).delete(any());
  }

  @Test
  @DisplayName("成本版本引用的旧最终价格永久保留")
  void keepsCostReferencedBatch() {
    PricePrepareBatch current = batch(30L, "PPR-NEW", "SUCCESS", 0);
    PricePrepareBatch old = batch(10L, "PPR-HISTORY", "SUCCESS", 0);
    QuoteCostingWorkspace workspace = workspace("PPR-HISTORY");
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setPricePrepareNo("PPR-HISTORY");
    when(workspaceService.lockOrCreate(any(), any(), any(), any(), any()))
        .thenReturn(workspace);
    when(batchMapper.selectList(any())).thenReturn(List.of(old));
    when(versionMapper.selectList(any())).thenReturn(List.of(version));

    service.finalizeBatch(current);

    verify(batchMapper, never()).delete(any());
  }

  @Test
  @DisplayName("协作缺口已持久化后删除未引用的失败价格候选")
  void discardsPromotedUnreferencedFailure() {
    PricePrepareBatch failed = batch(41L, "PPR-PROMOTED", "PARTIAL", 2);
    when(batchMapper.selectOne(any())).thenReturn(failed);
    when(workspaceService.find(101L, "2026-08")).thenReturn(Optional.empty());
    when(versionMapper.selectCount(any())).thenReturn(0L);
    when(batchMapper.selectCount(any())).thenReturn(0L);

    assertThat(service.discardPromotedFailedAttempt("PPR-PROMOTED")).isTrue();

    verify(gapMapper).delete(any());
    verify(batchMapper).delete(any());
  }

  @Test
  @DisplayName("工作区或成本引用的价格批次即使失败也禁止清理")
  void neverDiscardsReferencedAttempt() {
    PricePrepareBatch failed = batch(42L, "PPR-REFERENCED", "PARTIAL", 2);
    when(batchMapper.selectOne(any())).thenReturn(failed);
    when(workspaceService.find(101L, "2026-08"))
        .thenReturn(Optional.of(workspace("PPR-REFERENCED")));

    assertThat(service.discardPromotedFailedAttempt("PPR-REFERENCED")).isFalse();

    verify(gapMapper, never()).delete(any());
    verify(batchMapper, never()).delete(any());
  }

  private PricePrepareBatch batch(Long id, String prepareNo, String status, int gapCount) {
    PricePrepareBatch batch = new PricePrepareBatch();
    batch.setId(id);
    batch.setPrepareNo(prepareNo);
    batch.setOaNo("OA-1");
    batch.setOaFormItemId(101L);
    batch.setTopProductCode("TOP-1");
    batch.setPeriodMonth("2026-08");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setScenarioType(QuotePriceScenarioType.OA_LOCKED.name());
    batch.setStatus(status);
    batch.setGapCount(gapCount);
    batch.setWarningCount(0);
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 8, 19, 10, 0));
    return batch;
  }

  private QuoteCostingWorkspace workspace(String currentPrepareNo) {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setId(1L);
    workspace.setCurrentPrepareNo(currentPrepareNo);
    workspace.setLockVersion(0);
    workspace.setGapCount(0);
    return workspace;
  }
}
