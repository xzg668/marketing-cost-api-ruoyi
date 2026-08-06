package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomStatusServiceImplTest {
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private QuoteBomMonthlySnapshotMapper quoteBomMonthlySnapshotMapper;
  private BomAvailabilityAdapter bomAvailabilityAdapter;
  private BomU9SourceMapper bomU9SourceMapper;
  private U9ProductPackagingTypeResolver productPackagingTypeResolver;
  private QuoteBomStatusServiceImpl service;
  private Clock clock;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), BomU9Source.class);
  }

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    quoteBomMonthlySnapshotMapper = mock(QuoteBomMonthlySnapshotMapper.class);
    bomAvailabilityAdapter = mock(BomAvailabilityAdapter.class);
    bomU9SourceMapper = mock(BomU9SourceMapper.class);
    productPackagingTypeResolver = mock(U9ProductPackagingTypeResolver.class);
    clock = Clock.fixed(Instant.parse("2026-06-01T00:01:00Z"), ZoneId.of("UTC"));
    when(productPackagingTypeResolver.resolve(any(), any()))
        .thenReturn(U9ProductPackagingTypeResolver.Result.unknown(null));
    service =
        new QuoteBomStatusServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            quoteBomMonthlySnapshotMapper,
            bomAvailabilityAdapter,
            bomU9SourceMapper,
            productPackagingTypeResolver,
            new QuoteBomContextResolver(),
            clock);
  }

  @Test
  void productWithCurrentMonthCostingRowsUpdatesStatusToCurrentMonthQuoted() {
    stubFormAndItems(List.of(item(10L, 1, "MAT-1001", "SHF-A")), List.of());
    BomAvailability availability = available("COSTING_SNAPSHOT");
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-1001", "2026-06", "210"))
        .thenReturn(availability);
    when(productPackagingTypeResolver.resolve("MAT-1001", "COMMERCIAL"))
        .thenReturn(new U9ProductPackagingTypeResolver.Result(
            U9ProductPackagingTypeResolver.NAKED_PRODUCT, "110101"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("CURRENT_MONTH_QUOTED");
    assertThat(response.getItems().get(0).getProductPackagingType()).isEqualTo("NAKED_PRODUCT");
    assertThat(response.getItems().get(0).getMainCategoryCode()).isEqualTo("110101");
    ArgumentCaptor<QuoteBomStatus> captor = ArgumentCaptor.forClass(QuoteBomStatus.class);
    verify(quoteBomStatusMapper).insert(any(QuoteBomStatus.class));
    verify(quoteBomStatusMapper).updateById(captor.capture());
    assertThat(captor.getValue().getBomStatus()).isEqualTo("CURRENT_MONTH_QUOTED");
    assertThat(captor.getValue().getBomSource()).isEqualTo("COSTING_SNAPSHOT");
    assertThat(captor.getValue().getBomVersion()).isEqualTo("V1");
  }

  @Test
  void productWithRawHierarchyUpdatesStatusToU9BomExists() {
    stubFormAndItems(List.of(item(15L, 1, "MAT-U9-1", "SHF-U9")), List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-U9-1", "2026-06", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("U9_BOM_EXISTS");
  }

  @Test
  void productWithoutLocalBomUpdatesStatusToNoBom() {
    stubFormAndItems(List.of(item(11L, 1, "MAT-MISSING", "SHF-B")), List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-MISSING", "2026-06", "210"))
        .thenReturn(BomAvailability.unavailable("未匹配到本地正式 BOM 或有效补录 BOM"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getNoBomCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().get(0).getErrorMessage()).contains("未匹配");
  }

  @Test
  void productWithoutMaterialNoIsNoBomWithClearError() {
    stubFormAndItems(List.of(item(12L, 1, null, "SHF-C")), List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", null, "2026-06", "210"))
        .thenReturn(BomAvailability.unavailable("产品料号为空，无法自动匹配 BOM"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getNoBomCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().get(0).getErrorMessage()).contains("产品料号为空");
  }

  @Test
  void repeatedCheckUpdatesExistingStatusWithoutDuplicateInsert() {
    QuoteBomStatus existing = new QuoteBomStatus();
    existing.setId(99L);
    existing.setOaFormItemId(13L);
    existing.setOaNo("OA-T7-001");
    existing.setBomStatus("NOT_CHECKED");
    stubFormAndItems(List.of(item(13L, 1, "MAT-1002", "SHF-D")), List.of(existing));
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-1002", "2026-06", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    verify(quoteBomStatusMapper, never()).insert(any(QuoteBomStatus.class));
    verify(quoteBomStatusMapper).updateById(existing);
  }

  @Test
  void reusedCurrentMonthCountsAsSyncedInSummary() {
    QuoteBomStatus existing = new QuoteBomStatus();
    existing.setId(100L);
    existing.setOaFormItemId(14L);
    existing.setOaNo("OA-T7-001");
    existing.setBomStatus("REUSED_CURRENT_MONTH");
    existing.setProductCode("MAT-1003");
    stubFormAndItems(List.of(item(14L, 1, "MAT-1003", "SHF-E")), List.of(existing));

    QuoteBomStatusResponse response = service.listByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getUncheckedCount()).isZero();
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("REUSED_CURRENT_MONTH");
  }

  @Test
  void batchSyncDeduplicatesProductsAndReadsLocalU9Snapshot() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-T7-001");
    form.setCustomer("CUST-A");
    form.setAccountingPeriodMonth("2026-06");
    List<OaFormItem> items =
        List.of(item(21L, 1, "MAT-1001", "SHF-A"), item(22L, 2, "MAT-1001", "SHF-A2"));
    when(oaFormItemMapper.selectList(any())).thenReturn(items);
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any())).thenReturn(List.of(u9("MAT-1001", "BATCH-U9-1")));
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7100L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(21L, 22L));

    assertThat(response.getSelectedRowCount()).isEqualTo(2);
    assertThat(response.getDistinctProductCount()).isEqualTo(1);
    assertThat(response.getSyncedRowCount()).isEqualTo(2);
    assertThat(response.getNoBomRowCount()).isZero();
    verify(quoteBomStatusMapper, times(2)).insert(any(QuoteBomStatus.class));
    verify(quoteBomStatusMapper, times(2)).updateById(any(QuoteBomStatus.class));
    verify(quoteBomMonthlySnapshotMapper, times(2)).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void batchSyncMarksCheckFailedWhenLocalU9SnapshotMissing() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-T7-001");
    form.setCustomer("CUST-A");
    form.setAccountingPeriodMonth("2026-06");
    when(oaFormItemMapper.selectList(any()))
        .thenReturn(List.of(item(23L, 1, "MAT-MISSING", "SHF-M")));
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any())).thenReturn(List.of());

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(23L));

    assertThat(response.getSyncedRowCount()).isZero();
    assertThat(response.getNoBomRowCount()).isEqualTo(1);
    assertThat(response.getMissingProductCodes()).containsExactly("MAT-MISSING");
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("CHECK_FAILED");
    assertThat(response.getItems().get(0).getErrorMessage()).contains("本地 U9 全量快照");
    verify(quoteBomMonthlySnapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
    verify(quoteBomMonthlySnapshotMapper, never()).update(any(), any());
  }

  @Test
  void batchSyncSuccessRefreshesActiveManualSnapshot() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-T7-001");
    form.setCustomer(" CUST-N ");
    form.setAccountingPeriodMonth("2026-06");
    OaFormItem item = item(24L, 1, "MAT-1004", "SHF-N");
    item.setCustomerCode(" CUSTOMER-MATERIAL-N ");
    item.setPackageMethod(" BOX ");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any())).thenReturn(List.of(u9("MAT-1004", "BATCH-U9-MANUAL")));
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7200L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(24L));

    assertThat(response.getSyncedRowCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("SYNCED");
    assertThat(response.getItems().get(0).getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(response.getItems().get(0).getSyncRecordId()).isEqualTo(7200L);
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).update(any(), any());
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getSyncType()).isEqualTo("MANUAL");
    assertThat(snapshotCaptor.getValue().getSyncStatus()).isEqualTo("SUCCESS");
    assertThat(snapshotCaptor.getValue().getSyncBy()).isEqualTo("MANUAL");
    assertThat(snapshotCaptor.getValue().getActiveFlag()).isEqualTo(1);
    assertThat(snapshotCaptor.getValue().getCustomerCode()).isEqualTo("CUST-N");
    assertThat(snapshotCaptor.getValue().getPackageMethod()).isEqualTo("BOX");
  }

  @Test
  void batchSyncCannotReplaceFrozenMonthlyCardAndBindsItsEffectiveBuild() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-T7-001");
    form.setCustomer("CUST-A");
    form.setAccountingPeriodMonth("2026-06");
    OaFormItem item = item(26L, 1, "MAT-FROZEN", "SHF-FROZEN");
    item.setPackageMethod("BOX");
    QuoteBomMonthlySnapshot frozen =
        snapshot("MAT-FROZEN", "CUST-A", "BOX", "2026-06", 7260L);
    frozen.setFreezeStatus("FROZEN");
    frozen.setEffectiveBuildBatchId("BUILD-FROZEN-1");
    frozen.setEffectiveVariantHash("a".repeat(64));
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any()))
        .thenReturn(List.of(u9("MAT-FROZEN", "BATCH-U9-NEW")));
    when(quoteBomMonthlySnapshotMapper.selectList(any()))
        .thenReturn(List.of(frozen));

    QuoteBomBatchSyncResponse response =
        service.batchSyncFromU9Source(List.of(26L));

    assertThat(response.getSyncedRowCount()).isEqualTo(1);
    assertThat(response.getItems().getFirst().getBomStatus())
        .isEqualTo("REUSED_CURRENT_MONTH");
    verify(quoteBomMonthlySnapshotMapper, never())
        .insert(any(QuoteBomMonthlySnapshot.class));
    verify(quoteBomMonthlySnapshotMapper, never()).update(any(), any());
    ArgumentCaptor<QuoteBomStatus> statusCaptor =
        ArgumentCaptor.forClass(QuoteBomStatus.class);
    verify(quoteBomStatusMapper).updateById(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getSyncRecordId()).isEqualTo(7260L);
    assertThat(statusCaptor.getValue().getCostingBuildBatchId())
        .isEqualTo("BUILD-FROZEN-1");
  }

  @Test
  void batchSyncFromU9SourceUsesPriceOrganization() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("FI-SC-020-20260707-001");
    form.setCustomer("CUST-PLATE");
    form.setAccountingPeriodMonth("2026-06");
    OaFormItem item = item(25L, 1, "MAT-PLATE", "SHF-P");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
              assertThat(hasParamValue(wrapper, "220")).isTrue();
              return List.of(u9("MAT-PLATE", "BATCH-220", "220"));
            });
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7250L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(25L));

    assertThat(response.getSyncedRowCount()).isEqualTo(1);
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
    assertThat(snapshotCaptor.getValue().getBomBatchId()).isEqualTo("BATCH-220");
  }

  @Test
  void checkForCostRunFirstSuccessCreatesActiveSnapshotAndSyncedStatus() {
    OaFormItem item = item(31L, 1, "MAT-2001", "SHF-F");
    item.setCustomerCode(" ITEM-CUST ");
    item.setPackageMethod(" BOX ");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7001L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2001", "2026-06", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("U9_BOM_EXISTS");
    assertThat(response.getItems().get(0).getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(response.getItems().get(0).getSyncRecordId()).isEqualTo(7001L);
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getProductCode()).isEqualTo("MAT-2001");
    assertThat(snapshotCaptor.getValue().getCustomerCode()).isEqualTo("CUST-A");
    assertThat(snapshotCaptor.getValue().getPackageMethod()).isEqualTo("BOX");
    assertThat(snapshotCaptor.getValue().getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(snapshotCaptor.getValue().getSyncType()).isEqualTo("AUTO");
    assertThat(snapshotCaptor.getValue().getSyncStatus()).isEqualTo("SUCCESS");
    assertThat(snapshotCaptor.getValue().getSyncBy()).isEqualTo("SYSTEM");
    assertThat(snapshotCaptor.getValue().getActiveFlag()).isEqualTo(1);
  }

  @Test
  void checkSingleItemForCostRunCreatesSnapshotWithoutScanningOtherOaItems() {
    OaFormItem requested = item(31L, 1, "MAT-2001", "SHF-F");
    requested.setPackageMethod("BOX");
    OaFormItem other = item(32L, 2, "MAT-OTHER", "SHF-X");
    stubFormAndItems(List.of(requested, other), List.of());
    when(oaFormItemMapper.selectById(31L)).thenReturn(requested);
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7001L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));
    when(bomAvailabilityAdapter.findAvailableBom(
            "OA-T7-001", "MAT-2001", "2026-06", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusItemResponse response =
        service.checkItemForCostRun("OA-T7-001", 31L);

    assertThat(response.getProductCode()).isEqualTo("MAT-2001");
    assertThat(response.getSyncRecordId()).isEqualTo(7001L);
    verify(oaFormItemMapper, never()).selectList(any());
    verify(bomAvailabilityAdapter, times(1))
        .findAvailableBom("OA-T7-001", "MAT-2001", "2026-06", "210");
  }

  @Test
  void checkSingleItemUsesWorkbenchMonthInsteadOfHistoricalOaMonth() {
    OaFormItem requested = item(33L, 1, "MAT-2008", "SHF-AUG");
    requested.setPackageMethod("BOX");
    stubFormAndItems(List.of(requested), List.of());
    when(oaFormItemMapper.selectById(33L)).thenReturn(requested);
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7008L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));
    when(bomAvailabilityAdapter.findAvailableBom(
            "OA-T7-001", "MAT-2008", "2026-08", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusItemResponse response =
        service.checkItemForCostRun("OA-T7-001", 33L, "2026-08");

    assertThat(response.getCostPeriodMonth()).isEqualTo("2026-08");
    assertThat(response.getSyncRecordId()).isEqualTo(7008L);
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getCostPeriodMonth()).isEqualTo("2026-08");
    verify(bomAvailabilityAdapter)
        .findAvailableBom("OA-T7-001", "MAT-2008", "2026-08", "210");
  }

  @Test
  void checkForCostRunSecondSameKeyReusesCurrentMonthSnapshot() {
    QuoteBomMonthlySnapshot snapshot = snapshot("MAT-2002", "CUST-A", "BOX", "2026-06", 8001L);
    OaFormItem item = item(32L, 1, "MAT-2002", "SHF-G");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of(snapshot));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("REUSED_CURRENT_MONTH");
    assertThat(response.getItems().get(0).getReusedFromRecordId()).isEqualTo(8001L);
    assertThat(response.getItems().get(0).getSyncAt()).isEqualTo(snapshot.getSyncAt());
    verify(bomAvailabilityAdapter, never()).findAvailableBom(any(), any(), any(), any());
  }

  @Test
  void plateCostRunDoesNotReuseCommercialMonthlySnapshot() {
    QuoteBomMonthlySnapshot commercialSnapshot =
        snapshot("MAT-220", "CUST-A", "BOX", "2026-06", 8101L);
    OaFormItem item = item(38L, 1, "MAT-220", "SHF-P");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems("FI-SC-020-20260707-001", List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any()))
        .thenAnswer(
            invocation -> {
              AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
              boolean plateQuery = hasParamValue(wrapper, "220");
              return plateQuery ? List.of() : List.of(commercialSnapshot);
            });
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(8102L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));
    when(bomAvailabilityAdapter.findAvailableBom(
            "FI-SC-020-20260707-001", "MAT-220", "2026-06", "220"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkForCostRun("FI-SC-020-20260707-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("U9_BOM_EXISTS");
    verify(bomAvailabilityAdapter)
        .findAvailableBom("FI-SC-020-20260707-001", "MAT-220", "2026-06", "220");
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
  }

  @Test
  void fiSr005HeatExchangerChecksPlateOrganization() {
    OaFormItem item = item(39L, 1, "1053900000062", "S12BH-30L-19");
    item.setProductName("板式热交换器");
    stubFormAndItems("FI-SR-005-20260318-0397", List.of(item), List.of());
    when(bomAvailabilityAdapter.findAvailableBom(
            "FI-SR-005-20260318-0397", "1053900000062", "2026-06", "220"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkByOaNo("FI-SR-005-20260318-0397");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    verify(bomAvailabilityAdapter)
        .findAvailableBom("FI-SR-005-20260318-0397", "1053900000062", "2026-06", "220");
  }

  @Test
  void checkForCostRunDifferentCustomerDoesNotReuseAndChecksAgain() {
    OaFormItem item = item(33L, 1, "MAT-2003", "SHF-H");
    item.setCustomerCode("CUST-B");
    item.setPackageMethod("BOX");
    stubFormAndItems("OA-T7-001", "CUST-B", List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2003", "2026-06", "210"))
        .thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2003", "2026-06", "210");
  }

  @Test
  void checkForCostRunDifferentPackageMethodDoesNotReuseAndChecksAgain() {
    OaFormItem item = item(34L, 1, "MAT-2004", "SHF-I");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("PALLET");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2004", "2026-06", "210"))
        .thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2004", "2026-06", "210");
  }

  @Test
  void checkForCostRunCrossMonthDoesNotReusePreviousMonthSnapshot() {
    OaFormItem item = item(35L, 1, "MAT-2005", "SHF-J");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2005", "2026-06", "210"))
        .thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2005", "2026-06", "210");
  }

  @Test
  void checkForCostRunNoBomBlocksWholeResponse() {
    OaFormItem item = item(36L, 1, "MAT-MISSING", "SHF-K");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-MISSING", "2026-06", "210"))
        .thenReturn(BomAvailability.unavailable("未匹配到本地正式 BOM 或有效补录 BOM"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getNoBomCount()).isEqualTo(1);
    assertThat(response.getSyncedCount()).isZero();
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().get(0).getErrorMessage()).contains("未匹配");
  }

  @Test
  void legacySyncedStatusWithoutCostPeriodRechecksAndCreatesCurrentMonthSnapshot() {
    QuoteBomStatus legacy = new QuoteBomStatus();
    legacy.setId(9100L);
    legacy.setOaFormItemId(37L);
    legacy.setOaNo("OA-T7-001");
    legacy.setProductCode("MAT-LEGACY");
    legacy.setBomStatus("SYNCED");
    OaFormItem item = item(37L, 1, "MAT-LEGACY", "SHF-L");
    item.setCustomerCode("CUST-L");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of(legacy));
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    doAnswer(
            invocation -> {
              QuoteBomMonthlySnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(7300L);
              return 1;
            })
        .when(quoteBomMonthlySnapshotMapper)
        .insert(any(QuoteBomMonthlySnapshot.class));
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-LEGACY", "2026-06", "210"))
        .thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("U9_BOM_EXISTS");
    assertThat(response.getItems().get(0).getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(response.getItems().get(0).getSyncRecordId()).isEqualTo(7300L);
    verify(quoteBomStatusMapper, never()).insert(any(QuoteBomStatus.class));
    verify(quoteBomMonthlySnapshotMapper).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void costRunUsesOaAccountingMonthAndHeaderCustomerInsteadOfClockAndItemCustomerCode() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-CONTEXT-008");
    form.setProcessCode("FI-SC-006");
    form.setCustomer(" HEADER-CUSTOMER ");
    form.setAccountingPeriodMonth("2026-08");
    OaFormItem item = item(40L, 1, "MAT-CONTEXT", "SHF-CONTEXT");
    item.setCustomerCode("CUSTOMER-MATERIAL-NO");
    item.setPackageMethod(" BOX ");
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom(
            "OA-CONTEXT-008", "MAT-CONTEXT", "2026-08", "210"))
        .thenReturn(available("U9"));

    service.checkForCostRun("OA-CONTEXT-008");

    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getCostPeriodMonth()).isEqualTo("2026-08");
    assertThat(snapshotCaptor.getValue().getCustomerCode()).isEqualTo("HEADER-CUSTOMER");
    assertThat(snapshotCaptor.getValue().getPackageMethod()).isEqualTo("BOX");
  }

  @Test
  void manualSyncBlocksRawBomFromDifferentPriceOrganization() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-ORG-MISMATCH");
    form.setCustomer("CUST-A");
    form.setAccountingPeriodMonth("2026-08");
    OaFormItem item = item(41L, 1, "MAT-ORG-MISMATCH", "SHF-ORG");
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(oaFormMapper.selectBatchIds(any())).thenReturn(List.of(form));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>());
    when(bomU9SourceMapper.selectList(any()))
        .thenReturn(List.of(u9("MAT-ORG-MISMATCH", "BATCH-WRONG-ORG", "220")));

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(41L));

    assertThat(response.getSyncedRowCount()).isZero();
    assertThat(response.getNoBomRowCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("CHECK_FAILED");
    assertThat(response.getItems().get(0).getErrorMessage())
        .contains("210")
        .contains("220")
        .contains("不一致");
    verify(quoteBomMonthlySnapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
    verify(quoteBomMonthlySnapshotMapper, never()).update(any(), any());
  }

  private void stubFormAndItems(List<OaFormItem> items, List<QuoteBomStatus> statuses) {
    stubFormAndItems("OA-T7-001", items, statuses);
  }

  private boolean hasParamValue(AbstractWrapper<?, ?, ?> wrapper, String expectedValue) {
    if (wrapper == null) {
      return false;
    }
    wrapper.getSqlSegment();
    return wrapper.getParamNameValuePairs().values().stream()
        .map(String::valueOf)
        .anyMatch(expectedValue::equals);
  }

  private void stubFormAndItems(
      String oaNo, List<OaFormItem> items, List<QuoteBomStatus> statuses) {
    stubFormAndItems(oaNo, "CUST-A", items, statuses);
  }

  private void stubFormAndItems(
      String oaNo,
      String customer,
      List<OaFormItem> items,
      List<QuoteBomStatus> statuses) {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo(oaNo);
    form.setProcessCode(oaNo == null ? null : oaNo.split("-202")[0]);
    form.setCustomer(customer);
    form.setAccountingPeriodMonth("2026-06");
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectList(any())).thenReturn(items);
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>(statuses));
  }

  private OaFormItem item(Long id, Integer seq, String materialNo, String model) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(1L);
    item.setSeq(seq);
    item.setMaterialNo(materialNo);
    item.setSunlModel(model);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteBomMonthlySnapshot snapshot(
      String productCode, String customerCode, String packageMethod, String period, Long id) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setId(id);
    snapshot.setProductCode(productCode);
    snapshot.setCustomerCode(customerCode);
    snapshot.setPackageMethod(packageMethod);
    snapshot.setCostPeriodMonth(period);
    snapshot.setBomSource("U9");
    snapshot.setBomPurpose("10");
    snapshot.setBomVersion("V1");
    snapshot.setSyncStatus("SUCCESS");
    snapshot.setSyncType("AUTO");
    snapshot.setSyncAt(LocalDateTime.of(2026, 6, 1, 0, 0));
    snapshot.setBomBatchId("batch-source");
    snapshot.setActiveFlag(1);
    snapshot.setPriceOrgCode("210");
    return snapshot;
  }

  private BomAvailability available(String source) {
    BomAvailability availability = new BomAvailability();
    availability.setAvailable(true);
    availability.setSource(source);
    availability.setBomPurpose("10");
    availability.setBomVersion("V1");
    availability.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    availability.setEffectiveTo(LocalDate.of(2026, 12, 31));
    availability.setSyncBatchId("batch-1");
    return availability;
  }

  private BomU9Source u9(String parentMaterialNo, String batchId) {
    return u9(parentMaterialNo, batchId, "210");
  }

  private BomU9Source u9(String parentMaterialNo, String batchId, String priceOrgCode) {
    BomU9Source source = new BomU9Source();
    source.setPriceOrgCode(priceOrgCode);
    source.setParentMaterialNo(parentMaterialNo);
    source.setSourceType("EXCEL");
    source.setImportBatchId(batchId);
    source.setBomPurpose("10");
    source.setBomVersion("V1");
    source.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    source.setEffectiveTo(LocalDate.of(2026, 12, 31));
    source.setImportedAt(LocalDateTime.of(2026, 5, 12, 9, 0));
    return source;
  }
}
