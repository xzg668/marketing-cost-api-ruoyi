package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomStatusServiceImplTest {
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomAvailabilityAdapter bomAvailabilityAdapter;
  private BomU9SourceMapper bomU9SourceMapper;
  private QuoteBomStatusServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomAvailabilityAdapter = mock(BomAvailabilityAdapter.class);
    bomU9SourceMapper = mock(BomU9SourceMapper.class);
    service =
        new QuoteBomStatusServiceImpl(
            oaFormMapper, oaFormItemMapper, quoteBomStatusMapper, bomAvailabilityAdapter, bomU9SourceMapper);
  }

  @Test
  void productWithLocalBomUpdatesStatusToSynced() {
    stubFormAndItems(List.of(item(10L, 1, "MAT-1001", "SHF-A")), List.of());
    BomAvailability availability = available("U9");
    when(bomAvailabilityAdapter.findAvailableBom("OA-T7-001", "MAT-1001")).thenReturn(availability);

    QuoteBomStatusResponse response = service.checkByOaNo("OA-T7-001");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().get(0).getBomStatus()).isEqualTo("SYNCED");
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

    QuoteBomBatchSyncResponse response = service.batchSyncFromU9Source(List.of(21L, 22L));

    assertThat(response.getSelectedRowCount()).isEqualTo(2);
    assertThat(response.getDistinctProductCount()).isEqualTo(1);
    assertThat(response.getSyncedRowCount()).isEqualTo(2);
    assertThat(response.getNoBomRowCount()).isZero();
    verify(quoteBomStatusMapper, times(2)).insert(any(QuoteBomStatus.class));
    verify(quoteBomStatusMapper, times(2)).updateById(any(QuoteBomStatus.class));
  }

  @Test
  void batchSyncMarksNoBomWhenLocalU9SnapshotMissing() {
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
    assertThat(response.getItems().get(0).getErrorMessage()).contains("本地 U9 全量快照");
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
