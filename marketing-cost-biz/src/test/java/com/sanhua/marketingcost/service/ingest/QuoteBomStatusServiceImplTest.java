package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomStatusServiceImplTest {
  private final OaFormMapper formMapper = mock(OaFormMapper.class);
  private final OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
  private final QuoteBomStatusMapper statusMapper = mock(QuoteBomStatusMapper.class);
  private final QuoteBomMonthlySnapshotMapper snapshotMapper =
      mock(QuoteBomMonthlySnapshotMapper.class);
  private final QuoteCollaborationCurrentU9BomGateway u9Gateway =
      mock(QuoteCollaborationCurrentU9BomGateway.class);
  private final U9ProductPackagingTypeResolver packagingResolver =
      mock(U9ProductPackagingTypeResolver.class);
  private final CollaborationBomAvailabilityResolver collaborationResolver =
      mock(CollaborationBomAvailabilityResolver.class);
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-06-01T00:01:00Z"), ZoneId.of("UTC"));
  private QuoteBomStatusServiceImpl service;

  @BeforeEach
  void setUp() {
    when(packagingResolver.resolve(any(), any()))
        .thenReturn(U9ProductPackagingTypeResolver.Result.unknown(null));
    service = new QuoteBomStatusServiceImpl(
        formMapper, itemMapper, statusMapper, snapshotMapper, u9Gateway,
        packagingResolver, new QuoteBomContextResolver(), collaborationResolver, clock);
  }

  @Test
  void firstMonthlyAvailableU9LinksTheSharedSnapshot() {
    OaFormItem item = item(10L, "MAT-1", "BOX");
    stubQuote("OA-1", "CUST-A", "2026-06", item);
    QuoteBomMonthlySnapshot snapshot = u9Snapshot(7001L, "MAT-1", "2026-06", "SUCCESS");
    when(snapshotMapper.selectById(7001L)).thenReturn(snapshot);
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.available("U9", "V1", "BUILD-1", 8, "F1")
            .withMonthlySnapshot(7001L, true));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-1");

    assertThat(response.getSyncedCount()).isEqualTo(1);
    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("U9_BOM_EXISTS");
    assertThat(response.getItems().getFirst().getSyncRecordId()).isEqualTo(7001L);
    verify(snapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
    ArgumentCaptor<QuoteCollaborationScanContext> context =
        ArgumentCaptor.forClass(QuoteCollaborationScanContext.class);
    verify(u9Gateway).read(context.capture());
    assertThat(context.getValue().accountingMonth()).isEqualTo("2026-06");
    assertThat(context.getValue().priceOrgCode()).isEqualTo("210");
    assertThat(context.getValue().materialOrganizationCode()).isEqualTo("COMMERCIAL");
  }

  @Test
  void laterQuoteReusesFirstMonthlyU9Snapshot() {
    OaFormItem item = item(11L, "MAT-1", "PALLET");
    stubQuote("OA-2", "OTHER-CUSTOMER", "2026-06", item);
    QuoteBomMonthlySnapshot snapshot = u9Snapshot(7001L, "MAT-1", "2026-06", "SUCCESS");
    when(snapshotMapper.selectById(7001L)).thenReturn(snapshot);
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.available("U9", "V1", "BUILD-1", 8, "F1")
            .withMonthlySnapshot(7001L, false));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-2");

    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("REUSED_CURRENT_MONTH");
    assertThat(response.getItems().getFirst().getReusedFromRecordId()).isEqualTo(7001L);
    verify(snapshotMapper, never()).selectList(any());
    verify(snapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void monthlyNotFoundIsLinkedAndContinuesToApprovedElectronicChain() {
    OaFormItem item = item(12L, "MAT-MISSING", "BOX");
    stubQuote("OA-3", "CUST-A", "2026-06", item);
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.notFound("本月首次查询确认U9无BOM")
            .withMonthlySnapshot(7100L, false));
    when(snapshotMapper.selectList(any())).thenReturn(List.of());

    QuoteBomStatusResponse response = service.checkForCostRun("OA-3");

    assertThat(response.getNoBomCount()).isEqualTo(1);
    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().getFirst().getSyncRecordId()).isEqualTo(7100L);
    assertThat(response.getItems().getFirst().getReusedFromRecordId()).isEqualTo(7100L);
    verify(collaborationResolver).resolve(
        eq(12L), eq("COMMERCIAL"), eq("2026-06"), any());
  }

  @Test
  void approvedElectronicSnapshotRemainsSelectedAfterMonthlyU9NotFound() {
    OaFormItem item = item(13L, "MAT-MISSING", "BOX");
    stubQuote("OA-4", "CUST-A", "2026-06", item);
    QuoteBomMonthlySnapshot approved = approvedSnapshot(7200L, "MAT-MISSING", "2026-06");
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.notFound("本月首次查询确认U9无BOM")
            .withMonthlySnapshot(7100L, false));
    when(snapshotMapper.selectList(any())).thenReturn(List.of(approved));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-4");

    assertThat(response.getItems().getFirst().getBomSource())
        .isEqualTo("ELECTRONIC_DRAWING_BOM");
    assertThat(response.getItems().getFirst().getSyncRecordId()).isEqualTo(7200L);
    verify(collaborationResolver, never()).resolve(any(), any(), any(), any());
    verify(snapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void newlyApprovedElectronicCandidateCreatesOnlyQuoteScopedSnapshot() {
    OaFormItem item = item(14L, "MAT-MISSING", "BOX");
    stubQuote("OA-5", "CUST-A", "2026-06", item);
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.notFound("U9无BOM").withMonthlySnapshot(7100L, true));
    when(snapshotMapper.selectList(any())).thenReturn(List.of());
    when(collaborationResolver.resolve(eq(14L), eq("COMMERCIAL"), eq("2026-06"), any()))
        .thenReturn(electronicAvailability());
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot row = invocation.getArgument(0);
      row.setId(7300L);
      return 1;
    }).when(snapshotMapper).insert(any(QuoteBomMonthlySnapshot.class));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-5");

    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("MANUAL_ENTERED");
    ArgumentCaptor<QuoteBomMonthlySnapshot> row =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(snapshotMapper).insert(row.capture());
    assertThat(row.getValue().getSnapshotIdentityKey()).isNull();
    assertThat(row.getValue().getBomSource()).isEqualTo("ELECTRONIC_DRAWING_BOM");
  }

  @Test
  void u9QueryErrorDoesNotEnterSupplementOrCreateSnapshot() {
    OaFormItem item = item(15L, "MAT-ERROR", "BOX");
    stubQuote("OA-6", "CUST-A", "2026-06", item);
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.timeout("U9查询超时"));

    QuoteBomStatusResponse response = service.checkForCostRun("OA-6");

    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("CHECK_FAILED");
    assertThat(response.getItems().getFirst().getErrorMessage()).contains("超时");
    verify(collaborationResolver, never()).resolve(any(), any(), any(), any());
    verify(snapshotMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void singleItemUsesWorkbenchMonth() {
    OaFormItem item = item(16L, "MAT-AUG", "BOX");
    stubQuote("OA-7", "CUST-A", "2026-06", item);
    when(itemMapper.selectById(16L)).thenReturn(item);
    QuoteBomMonthlySnapshot snapshot = u9Snapshot(7400L, "MAT-AUG", "2026-08", "SUCCESS");
    when(snapshotMapper.selectById(7400L)).thenReturn(snapshot);
    when(u9Gateway.read(any())).thenReturn(
        CurrentU9BomResult.available("U9", "V2", "BUILD-AUG", 5)
            .withMonthlySnapshot(7400L, true));

    QuoteBomStatusItemResponse response =
        service.checkItemForCostRun("OA-7", 16L, "2026-08");

    assertThat(response.getCostPeriodMonth()).isEqualTo("2026-08");
    ArgumentCaptor<QuoteCollaborationScanContext> context =
        ArgumentCaptor.forClass(QuoteCollaborationScanContext.class);
    verify(u9Gateway).read(context.capture());
    assertThat(context.getValue().accountingMonth()).isEqualTo("2026-08");
    verify(itemMapper, never()).selectList(any());
  }

  @Test
  void productWithoutMaterialNumberNeverQueriesU9() {
    OaFormItem item = item(17L, null, "BOX");
    item.setSunlModel("MODEL-NEW-17");
    stubQuote("OA-8", "CUST-A", "2026-06", item);

    QuoteBomStatusResponse response = service.checkForCostRun("OA-8");

    assertThat(response.getItems().getFirst().getBomStatus()).isEqualTo("NO_BOM");
    assertThat(response.getItems().getFirst().getProductCode())
        .isEqualTo("MODEL:MODEL-NEW-17");
    assertThat(response.getItems().getFirst().getErrorMessage()).contains("新品暂无正式料号");
    verify(u9Gateway, never()).read(any());
  }

  private void stubQuote(String oaNo, String customer, String month, OaFormItem item) {
    when(formMapper.selectOne(any())).thenReturn(form(oaNo, customer, month));
    when(itemMapper.selectList(any())).thenReturn(List.of(item));
    when(statusMapper.selectList(any())).thenReturn(new ArrayList<>());
  }

  private OaForm form(String oaNo, String customer, String month) {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo(oaNo);
    form.setProcessCode("FI-SC-006");
    form.setBusinessUnitType("COMMERCIAL");
    form.setCustomer(customer);
    form.setAccountingPeriodMonth(month);
    return form;
  }

  private OaFormItem item(Long id, String productCode, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(1L);
    item.setSeq(1);
    item.setMaterialNo(productCode);
    item.setSunlModel("MODEL");
    item.setPackageMethod(packageMethod);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteBomMonthlySnapshot u9Snapshot(
      Long id, String productCode, String month, String status) {
    QuoteBomMonthlySnapshot row = new QuoteBomMonthlySnapshot();
    row.setId(id);
    row.setProductCode(productCode);
    row.setPriceOrgCode("210");
    row.setMaterialOrganizationCode("COMMERCIAL");
    row.setBusinessUnitType("COMMERCIAL");
    row.setSnapshotIdentityKey("A".repeat(64));
    row.setCostPeriodMonth(month);
    row.setBomSource("U9");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setBomBatchId("BUILD-1");
    row.setSyncStatus(status);
    row.setSyncAt(LocalDateTime.of(2026, 6, 1, 0, 1));
    row.setActiveFlag(1);
    return row;
  }

  private QuoteBomMonthlySnapshot approvedSnapshot(Long id, String productCode, String month) {
    QuoteBomMonthlySnapshot row = u9Snapshot(id, productCode, month, "SUCCESS");
    row.setSnapshotIdentityKey(null);
    row.setCustomerCode("CUST-A");
    row.setPackageMethod("BOX");
    row.setBomSource("ELECTRONIC_DRAWING_BOM");
    row.setBomBatchId("SUPPLEMENT_VERSION:90");
    return row;
  }

  private BomAvailability electronicAvailability() {
    BomAvailability result = new BomAvailability();
    result.setAvailable(true);
    result.setSource("ELECTRONIC_DRAWING_BOM");
    result.setBomPurpose("完整BOM");
    result.setBomVersion("ED-90");
    result.setSyncBatchId("SUPPLEMENT_VERSION:90");
    return result;
  }
}
