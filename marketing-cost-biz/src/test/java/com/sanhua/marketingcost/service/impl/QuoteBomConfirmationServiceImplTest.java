package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomConfirmationLog;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeConfirmationGuard;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomConfirmationServiceImplTest {

  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private QuoteBomConfirmationMapper confirmationMapper;
  private QuoteBomConfirmationLogMapper confirmationLogMapper;
  private QuoteCostRunVersionInvalidationService versionInvalidationService;
  private QuoteBomPreparationRecordMapper preparationRecordMapper;
  private QuoteBomAlternativeConfirmationGuard alternativeConfirmationGuard;
  private QuoteBomConfirmationServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    confirmationMapper = mock(QuoteBomConfirmationMapper.class);
    confirmationLogMapper = mock(QuoteBomConfirmationLogMapper.class);
    versionInvalidationService = mock(QuoteCostRunVersionInvalidationService.class);
    preparationRecordMapper = mock(QuoteBomPreparationRecordMapper.class);
    alternativeConfirmationGuard = mock(QuoteBomAlternativeConfirmationGuard.class);
    service =
        new QuoteBomConfirmationServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            confirmationMapper,
            confirmationLogMapper,
            versionInvalidationService,
            preparationRecordMapper,
            alternativeConfirmationGuard,
            new QuoteBomContextResolver());
  }

  @Test
  void confirmRejectsEmptyBomRows() {
    mockScope();
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.confirm("OA-001", 10L, new QuoteBomConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("BOM 明细为空");
    verify(confirmationMapper, never()).insert(any(QuoteBomConfirmation.class));
  }

  @Test
  void confirmRejectsQuoteItemOutsideOa() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    OaFormItem item = item();
    item.setOaFormId(99L);
    when(oaFormItemMapper.selectById(10L)).thenReturn(item);

    assertThatThrownBy(() -> service.confirm("OA-001", 10L, new QuoteBomConfirmRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不属于当前报价单");
    verify(bomCostingRowMapper, never()).selectQuoteCostingSnapshot(any(), any(), any(), any());
  }

  @Test
  void confirmCreatesConfirmedVersionAndLog() {
    mockScope();
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row("MAT-1", 0), row("MAT-2", 1)));
    when(confirmationMapper.selectList(any())).thenReturn(List.of(), List.of());
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class)))
        .thenAnswer(
            invocation -> {
              QuoteBomConfirmation entity = invocation.getArgument(0);
              entity.setId(701L);
              return 1;
            });

    QuoteBomConfirmRequest request = new QuoteBomConfirmRequest();
    request.setConfirmRemark("确认报价物料");
    QuoteBomConfirmResponse response = service.confirm("OA-001", 10L, request);

    assertThat(response.getId()).isEqualTo(701L);
    assertThat(response.getConfirmNo()).startsWith("BOM-CF-");
    assertThat(response.getConfirmStatus()).isEqualTo(QuoteBomConfirmation.STATUS_CONFIRMED);
    assertThat(response.getConfirmVersion()).isEqualTo(1);
    assertThat(response.getRowCount()).isEqualTo(2);
    assertThat(response.getManualModifiedCount()).isEqualTo(1);
    assertThat(response.getReplaceCount()).isZero();

    ArgumentCaptor<QuoteBomConfirmationLog> logCaptor =
        ArgumentCaptor.forClass(QuoteBomConfirmationLog.class);
    verify(confirmationLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getActionType()).isEqualTo(QuoteBomConfirmationLog.ACTION_CONFIRM);
    assertThat(logCaptor.getValue().getAfterStatus()).isEqualTo(QuoteBomConfirmation.STATUS_CONFIRMED);
    verify(versionInvalidationService)
        .invalidateProduct("OA-001", 10L, "FIN-001", "2026-06");
  }

  @Test
  void repeatConfirmReturnsExistingVersionWithoutWritingDuplicateData() {
    mockScope();
    QuoteBomConfirmation old = existingConfirmation(1, QuoteBomConfirmation.STATUS_CONFIRMED);
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row("MAT-1", 0)));
    when(confirmationMapper.selectList(any())).thenReturn(List.of(old));

    QuoteBomConfirmResponse response = service.confirm("OA-001", 10L, new QuoteBomConfirmRequest());

    assertThat(response.getConfirmVersion()).isEqualTo(1);
    assertThat(response.getConfirmNo()).isEqualTo(old.getConfirmNo());
    verify(confirmationMapper, never()).insert(any(QuoteBomConfirmation.class));
    verify(confirmationMapper, never()).updateById(any(QuoteBomConfirmation.class));
    verify(confirmationLogMapper, never()).insert(any(QuoteBomConfirmationLog.class));
    verify(versionInvalidationService, never())
        .invalidateProduct(any(), any(), any(), any());
  }

  @Test
  void cancelConfirmInvalidatesLatestConfirmedAndWritesLog() {
    mockScope();
    QuoteBomConfirmation latest = existingConfirmation(1, QuoteBomConfirmation.STATUS_CONFIRMED);
    when(confirmationMapper.selectOne(any())).thenReturn(latest);
    when(confirmationMapper.updateById(any(QuoteBomConfirmation.class))).thenReturn(1);
    QuoteBomCancelConfirmRequest request = new QuoteBomCancelConfirmRequest();
    request.setCancelRemark("撤销后调整用量");

    QuoteBomConfirmResponse response = service.cancelConfirm("OA-001", 10L, request);

    assertThat(response.getConfirmStatus()).isEqualTo(QuoteBomConfirmation.STATUS_INVALID);
    verify(confirmationMapper).updateById(latest);
    ArgumentCaptor<QuoteBomConfirmationLog> logCaptor =
        ArgumentCaptor.forClass(QuoteBomConfirmationLog.class);
    verify(confirmationLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getActionType()).isEqualTo(QuoteBomConfirmationLog.ACTION_CANCEL);
    assertThat(logCaptor.getValue().getBeforeStatus()).isEqualTo(QuoteBomConfirmation.STATUS_CONFIRMED);
    assertThat(logCaptor.getValue().getAfterStatus()).isEqualTo(QuoteBomConfirmation.STATUS_INVALID);
    assertThat(logCaptor.getValue().getRemark()).isEqualTo("撤销后调整用量");
    verify(versionInvalidationService)
        .invalidateProduct("OA-001", 10L, "FIN-001", "2026-06");
  }

  @Test
  void activeConfirmationCheckUsesExactQuoteProductMonthScope() {
    when(confirmationMapper.selectCount(any()))
        .thenReturn(1L);

    boolean active =
        service.hasActiveConfirmation(
            "OA-001", 10L, "FIN-001", "2026-06");

    assertThat(active).isTrue();
    verify(confirmationMapper).selectCount(any());
  }

  @Test
  void effectiveConfirmationPersistsTheSharedBuildAndSkipsLiveAlternativeGuard() {
    mockScope();
    QuoteBomStatus status = status();
    status.setCostingBuildBatchId("qeb_BUILD_1");
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status);
    BomCostingRow first = row("MAT-1", 0);
    first.setBuildBatchId("qeb_BUILD_1");
    BomCostingRow second = row("MAT-2", 0);
    second.setBuildBatchId("qeb_BUILD_1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(first, second));
    when(confirmationMapper.selectList(any())).thenReturn(List.of(), List.of());
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class)))
        .thenAnswer(
            invocation -> {
              invocation.<QuoteBomConfirmation>getArgument(0).setId(801L);
              return 1;
            });

    QuoteBomConfirmResponse response =
        service.confirmEffective(
            "OA-001", 10L, "qeb_BUILD_1", 1, new QuoteBomConfirmRequest());

    assertThat(response.getCostingBuildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(response.getReplaceCount()).isOne();
    ArgumentCaptor<QuoteBomConfirmation> confirmation =
        ArgumentCaptor.forClass(QuoteBomConfirmation.class);
    verify(confirmationMapper).insert(confirmation.capture());
    assertThat(confirmation.getValue().getCostingBuildBatchId())
        .isEqualTo("qeb_BUILD_1");
    verify(alternativeConfirmationGuard, never())
        .validateAndCountManualAlternatives(any(), any(), any());
    verify(versionInvalidationService)
        .invalidateProduct("OA-001", 10L, "FIN-001", "2026-06");
  }

  @Test
  void effectiveConfirmationRejectsRowsOrStatusFromAnotherBuild() {
    mockScope();
    QuoteBomStatus status = status();
    status.setCostingBuildBatchId("qeb_BUILD_1");
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status);
    BomCostingRow wrong = row("MAT-1", 0);
    wrong.setBuildBatchId("OTHER");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(wrong));
    when(confirmationMapper.selectList(any())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.confirmEffective(
                    "OA-001", 10L, "qeb_BUILD_1", 0, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("结算行");
    verify(confirmationMapper, never()).insert(any(QuoteBomConfirmation.class));
  }

  @Test
  void repeatEffectiveConfirmationRequiresTheSameBuildBatch() {
    mockScope();
    BomCostingRow row = row("MAT-1", 0);
    row.setBuildBatchId("qeb_BUILD_1");
    when(bomCostingRowMapper.selectQuoteCostingSnapshot(
            "OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row));
    QuoteBomConfirmation existing =
        existingConfirmation(1, QuoteBomConfirmation.STATUS_CONFIRMED);
    existing.setCostingBuildBatchId("OTHER");
    when(confirmationMapper.selectList(any())).thenReturn(List.of(existing));

    assertThatThrownBy(
            () ->
                service.confirmEffective(
                    "OA-001", 10L, "qeb_BUILD_1", 0, null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("已有BOM确认");
  }

  private void mockScope() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
    when(preparationRecordMapper.selectOne(any())).thenReturn(preparation());
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-001");
    form.setAccountingPeriodMonth("2026-06");
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(1L);
    item.setMaterialNo("FIN-001");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteBomStatus status() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(101L);
    status.setOaNo("OA-001");
    status.setOaFormItemId(10L);
    status.setProductCode("FIN-001");
    status.setCostPeriodMonth("2026-06");
    return status;
  }

  private QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord preparation =
        new QuoteBomPreparationRecord();
    preparation.setId(90L);
    preparation.setOaFormId(1L);
    preparation.setOaFormItemId(10L);
    preparation.setOaNo("OA-001");
    preparation.setQuoteProductCode("FIN-001");
    preparation.setProductType("NON_BARE");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    return preparation;
  }

  private BomCostingRow row(String materialCode, int manualModified) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setOaFormItemId(10L);
    row.setTopProductCode("FIN-001");
    row.setMaterialCode(materialCode);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setPeriodMonth("2026-06");
    row.setManualModified(manualModified);
    return row;
  }

  private QuoteBomConfirmation existingConfirmation(int version, String status) {
    QuoteBomConfirmation confirmation = new QuoteBomConfirmation();
    confirmation.setId(700L + version);
    confirmation.setConfirmNo("BOM-CF-OLD-" + version);
    confirmation.setOaNo("OA-001");
    confirmation.setOaFormItemId(10L);
    confirmation.setTopProductCode("FIN-001");
    confirmation.setPeriodMonth("2026-06");
    confirmation.setConfirmStatus(status);
    confirmation.setConfirmVersion(version);
    confirmation.setRowCount(1);
    confirmation.setManualModifiedCount(0);
    confirmation.setReplaceCount(0);
    confirmation.setConfirmedBy("system");
    confirmation.setConfirmedAt(LocalDateTime.now().minusMinutes(5));
    confirmation.setBusinessUnitType("COMMERCIAL");
    return confirmation;
  }
}
