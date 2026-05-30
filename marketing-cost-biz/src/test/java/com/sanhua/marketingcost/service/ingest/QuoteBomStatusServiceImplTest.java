package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
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
    when(productPackagingTypeResolver.resolve(any()))
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
            clock);
  }

  @Test
  void productWithLocalBomUpdatesStatusToSynced() {
    stubFormAndItems(List.of(item(10L, 1, "MAT-1001", "SHF-A")), List.of());
    BomAvailability availability = available("U9");
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-1001")).thenReturn(availability);
    when(productPackagingTypeResolver.resolve("MAT-1001"))
        .thenReturn(new U9ProductPackagingTypeResolver.Result(
            U9ProductPackagingTypeResolver.NAKED_PRODUCT, "110101"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("SYNCED");
    assertThat(response.getItems().get(0).getProductPackagingType()).isEqualTo("NAKED_PRODUCT");
    assertThat(response.getItems().get(0).getMainCategoryCode()).isEqualTo("110101");
    ArgumentCaptor<QuoteBomStatus> captor = ArgumentCaptor.forClass(QuoteBomStatus.class);
    verify(quoteBomStatusMapper).insert(any(QuoteBomStatus.class));
    verify(quoteBomStatusMapper).updateById(captor.capture());
    assertThat(captor.getValue().getBomStatus()).isEqualTo("SYNCED");
    assertThat(captor.getValue().getBomSource()).isEqualTo("U9");
    assertThat(captor.getValue().getBomVersion()).isEqualTo("V1");
  }

  @Test
  void productWithoutLocalBomUpdatesStatusToNoBom() {
    stubFormAndItems(List.of(item(11L, 1, "MAT-MISSING", "SHF-B")), List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-MISSING"))
        .thenReturn(BomAvailability.unavailable("未匹配到本地正式 BOM 或有效补录 BOM"));

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getNoBomCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().get(0).getErrorMessage()).contains("未匹配");
  }

  @Test
  void productWithoutMaterialNoIsNoBomWithClearError() {
    stubFormAndItems(List.of(item(12L, 1, null, "SHF-C")), List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", null))
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
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-1002")).thenReturn(available("U9"));

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
    OaFormItem item = item(24L, 1, "MAT-1004", "SHF-N");
    item.setCustomerCode(" CUST-N ");
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
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2001")).thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("SYNCED");
    assertThat(response.getItems().get(0).getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(response.getItems().get(0).getSyncRecordId()).isEqualTo(7001L);
    ArgumentCaptor<QuoteBomMonthlySnapshot> snapshotCaptor =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(quoteBomMonthlySnapshotMapper).insert(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getProductCode()).isEqualTo("MAT-2001");
    assertThat(snapshotCaptor.getValue().getCustomerCode()).isEqualTo("ITEM-CUST");
    assertThat(snapshotCaptor.getValue().getPackageMethod()).isEqualTo("BOX");
    assertThat(snapshotCaptor.getValue().getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(snapshotCaptor.getValue().getSyncType()).isEqualTo("AUTO");
    assertThat(snapshotCaptor.getValue().getSyncStatus()).isEqualTo("SUCCESS");
    assertThat(snapshotCaptor.getValue().getSyncBy()).isEqualTo("SYSTEM");
    assertThat(snapshotCaptor.getValue().getActiveFlag()).isEqualTo(1);
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
    verify(bomAvailabilityAdapter, never()).findAvailableBom(any(), any());
  }

  @Test
  void checkForCostRunDifferentCustomerDoesNotReuseAndChecksAgain() {
    OaFormItem item = item(33L, 1, "MAT-2003", "SHF-H");
    item.setCustomerCode("CUST-B");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2003")).thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2003");
  }

  @Test
  void checkForCostRunDifferentPackageMethodDoesNotReuseAndChecksAgain() {
    OaFormItem item = item(34L, 1, "MAT-2004", "SHF-I");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("PALLET");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2004")).thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2004");
  }

  @Test
  void checkForCostRunCrossMonthDoesNotReusePreviousMonthSnapshot() {
    OaFormItem item = item(35L, 1, "MAT-2005", "SHF-J");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-2005")).thenReturn(available("U9"));

    service.checkForCostRun("OA-T7-001");

    verify(bomAvailabilityAdapter).findAvailableBom("OA-T7-001", "MAT-2005");
  }

  @Test
  void checkForCostRunNoBomBlocksWholeResponse() {
    OaFormItem item = item(36L, 1, "MAT-MISSING", "SHF-K");
    item.setCustomerCode("CUST-A");
    item.setPackageMethod("BOX");
    stubFormAndItems(List.of(item), List.of());
    when(quoteBomMonthlySnapshotMapper.selectList(any())).thenReturn(List.of());
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-MISSING"))
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
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-LEGACY")).thenReturn(available("U9"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("SYNCED");
    assertThat(response.getItems().get(0).getCostPeriodMonth()).isEqualTo("2026-06");
    assertThat(response.getItems().get(0).getSyncRecordId()).isEqualTo(7300L);
    verify(quoteBomStatusMapper, never()).insert(any(QuoteBomStatus.class));
    verify(quoteBomMonthlySnapshotMapper).insert(any(QuoteBomMonthlySnapshot.class));
  }

  private void stubFormAndItems(List<OaFormItem> items, List<QuoteBomStatus> statuses) {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-T7-001");
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
    BomU9Source source = new BomU9Source();
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
