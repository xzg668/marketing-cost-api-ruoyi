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
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
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
  private QuoteBomConfirmationServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    bomCostingRowMapper = mock(BomCostingRowMapper.class);
    confirmationMapper = mock(QuoteBomConfirmationMapper.class);
    confirmationLogMapper = mock(QuoteBomConfirmationLogMapper.class);
    service =
        new QuoteBomConfirmationServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            quoteBomStatusMapper,
            bomCostingRowMapper,
            confirmationMapper,
            confirmationLogMapper);
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
    assertThat(response.getUsageAdjustCount()).isZero();

    ArgumentCaptor<QuoteBomConfirmationLog> logCaptor =
        ArgumentCaptor.forClass(QuoteBomConfirmationLog.class);
    verify(confirmationLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getActionType()).isEqualTo(QuoteBomConfirmationLog.ACTION_CONFIRM);
    assertThat(logCaptor.getValue().getAfterStatus()).isEqualTo(QuoteBomConfirmation.STATUS_CONFIRMED);
  }

  @Test
  void repeatConfirmInvalidatesOldAndCreatesNextVersion() {
    mockScope();
    QuoteBomConfirmation old = existingConfirmation(1, QuoteBomConfirmation.STATUS_CONFIRMED);
    when(bomCostingRowMapper.selectQuoteCostingSnapshot("OA-001", 10L, "FIN-001", "2026-06"))
        .thenReturn(List.of(row("MAT-1", 0)));
    when(confirmationMapper.selectList(any())).thenReturn(List.of(old), List.of(old));
    when(confirmationMapper.updateById(any(QuoteBomConfirmation.class))).thenReturn(1);
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class))).thenReturn(1);

    QuoteBomConfirmResponse response = service.confirm("OA-001", 10L, new QuoteBomConfirmRequest());

    assertThat(response.getConfirmVersion()).isEqualTo(2);
    assertThat(old.getConfirmStatus()).isEqualTo(QuoteBomConfirmation.STATUS_INVALID);
    verify(confirmationMapper).updateById(old);
    ArgumentCaptor<QuoteBomConfirmationLog> logCaptor =
        ArgumentCaptor.forClass(QuoteBomConfirmationLog.class);
    verify(confirmationLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
    assertThat(logCaptor.getAllValues()).extracting("actionType")
        .containsExactly(QuoteBomConfirmationLog.ACTION_STALE, QuoteBomConfirmationLog.ACTION_CONFIRM);
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
  }

  private void mockScope() {
    when(oaFormMapper.selectOne(any())).thenReturn(form());
    when(oaFormItemMapper.selectById(10L)).thenReturn(item());
    when(quoteBomStatusMapper.selectOne(any())).thenReturn(status());
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
    confirmation.setUsageAdjustCount(0);
    confirmation.setConfirmedBy("system");
    confirmation.setConfirmedAt(LocalDateTime.now().minusMinutes(5));
    confirmation.setBusinessUnitType("COMMERCIAL");
    return confirmation;
  }
}
