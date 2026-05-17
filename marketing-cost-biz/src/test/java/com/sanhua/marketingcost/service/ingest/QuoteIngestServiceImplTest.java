package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.enums.QuoteIngestStatus;
import com.sanhua.marketingcost.mapper.OaFormExtraFeeMapper;
import com.sanhua.marketingcost.mapper.OaFormExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteIngestServiceImplTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private QuoteIngestLogService quoteIngestLogService;
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private OaFormExtraFeeMapper oaFormExtraFeeMapper;
  private OaFormExtraFieldMapper oaFormExtraFieldMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private QuoteIngestServiceImpl service;

  @BeforeEach
  void setUp() {
    quoteIngestLogService = mock(QuoteIngestLogService.class);
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    oaFormExtraFeeMapper = mock(OaFormExtraFeeMapper.class);
    oaFormExtraFieldMapper = mock(OaFormExtraFieldMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    service =
        new QuoteIngestServiceImpl(
            new QuoteNormalizeService(new QuoteIngestRequestValidator(), new QuoteClassifyService()),
            quoteIngestLogService,
            oaFormMapper,
            oaFormItemMapper,
            oaFormExtraFeeMapper,
            oaFormExtraFieldMapper,
            quoteBomStatusMapper,
            objectMapper);
    stubInsertIds();
  }

  @Test
  void minimalFiSc006CreatesFormItemIngestLogAndBomStatus() {
    QuoteIngestRequest request = request("FI-SC-006", "EXT-T5-001", "1001", "批量品");
    QuoteIngestLog log = log(10L, "EXCEL:EXT-T5-001:1", "old");
    when(quoteIngestLogService.findByIdempotencyKey("EXCEL:EXT-T5-001:1")).thenReturn(null);
    when(quoteIngestLogService.createReceived(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(log);

    QuoteIngestResponse response = service.ingest(request);

    assertThat(response.isAccepted()).isTrue();
    assertThat(response.getOaFormId()).isEqualTo(100L);
    assertThat(response.getOaNo()).isEqualTo("EXT-T5-001");
    assertThat(response.getClassificationStatus()).isEqualTo("CONFIRMED");
    assertThat(response.getQuoteScenario()).isEqualTo("STANDARD_BATCH");

    ArgumentCaptor<OaForm> formCaptor = ArgumentCaptor.forClass(OaForm.class);
    verify(oaFormMapper).insert(formCaptor.capture());
    assertThat(formCaptor.getValue().getProcessCode()).isEqualTo("FI-SC-006");
    assertThat(formCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");

    ArgumentCaptor<OaFormItem> itemCaptor = ArgumentCaptor.forClass(OaFormItem.class);
    verify(oaFormItemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getOaFormId()).isEqualTo(100L);
    assertThat(itemCaptor.getValue().getMaterialNo()).isEqualTo("1001");

    ArgumentCaptor<QuoteBomStatus> bomCaptor = ArgumentCaptor.forClass(QuoteBomStatus.class);
    verify(quoteBomStatusMapper).insert(bomCaptor.capture());
    assertThat(bomCaptor.getValue().getBomStatus()).isEqualTo("NOT_CHECKED");
    verify(quoteIngestLogService).markImported(any(), any(), any(), any());
  }

  @Test
  void duplicateSamePayloadDoesNotCreateAgain() throws Exception {
    QuoteIngestRequest request = request("FI-SC-006", "EXT-T5-DUP", "1001", "批量品");
    QuoteIngestLog existing = log(11L, "EXCEL:EXT-T5-DUP:1", sha256(objectMapper.writeValueAsString(request)));
    existing.setOaNo("EXT-T5-DUP");
    existing.setIngestStatus(QuoteIngestStatus.IMPORTED.getCode());
    when(quoteIngestLogService.findByIdempotencyKey("EXCEL:EXT-T5-DUP:1")).thenReturn(existing);

    QuoteIngestResponse response = service.ingest(request);

    assertThat(response.isAccepted()).isTrue();
    assertThat(response.getIngestLogId()).isEqualTo(11L);
    assertThat(response.getOaNo()).isEqualTo("EXT-T5-DUP");
    verify(quoteIngestLogService, never()).createReceived(any(), any(), any(), any(), any(), any(), any());
    verify(oaFormMapper, never()).insert(any(OaForm.class));
    verify(oaFormItemMapper, never()).insert(any(OaFormItem.class));
  }

  @Test
  void sameIdempotencyDifferentPayloadUpdatesExistingForm() {
    QuoteIngestRequest request = request("FI-SC-006", "EXT-T5-UPD", "1002", "批量品");
    QuoteIngestLog existingLog = log(12L, "EXCEL:EXT-T5-UPD:1", "different-hash");
    OaForm existingForm = new OaForm();
    existingForm.setId(200L);
    existingForm.setOaNo("EXT-T5-UPD");
    existingForm.setCalcStatus("未核算");
    when(quoteIngestLogService.findByIdempotencyKey("EXCEL:EXT-T5-UPD:1")).thenReturn(existingLog);
    when(oaFormMapper.selectOne(any())).thenReturn(existingForm);

    QuoteIngestResponse response = service.ingest(request);

    assertThat(response.isAccepted()).isTrue();
    assertThat(response.getOaFormId()).isEqualTo(200L);
    verify(quoteIngestLogService).refreshReceived(any(), any(), any(), any(), any(), any(), any());
    verify(oaFormMapper, never()).insert(any(OaForm.class));
    verify(oaFormMapper).updateById(existingForm);
    verify(oaFormItemMapper).delete(any());
    verify(oaFormItemMapper).insert(any(OaFormItem.class));
  }

  @Test
  void fiSr005WithoutBusinessTypeCreatesPendingClassification() {
    QuoteIngestRequest request = request("FI-SR-005", "EXT-T5-PENDING", "1003", null);
    QuoteIngestLog log = log(13L, "EXCEL:EXT-T5-PENDING:1", "old");
    when(quoteIngestLogService.createReceived(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(log);

    QuoteIngestResponse response = service.ingest(request);

    assertThat(response.isAccepted()).isTrue();
    assertThat(response.getClassificationStatus()).isEqualTo("PENDING");
    assertThat(response.getQuoteScenario()).isEqualTo("UNKNOWN");
    ArgumentCaptor<OaForm> formCaptor = ArgumentCaptor.forClass(OaForm.class);
    verify(oaFormMapper).insert(formCaptor.capture());
    assertThat(formCaptor.getValue().getClassificationStatus()).isEqualTo("PENDING");
    assertThat(formCaptor.getValue().getQuoteScenario()).isEqualTo("UNKNOWN");
  }

  @Test
  void calculatedFormRejectsOverwrite() {
    QuoteIngestRequest request = request("FI-SC-006", "EXT-T5-CALC", "1004", "批量品");
    QuoteIngestLog log = log(14L, "EXCEL:EXT-T5-CALC:1", "old");
    OaForm calculated = new OaForm();
    calculated.setId(300L);
    calculated.setOaNo("EXT-T5-CALC");
    calculated.setCalcStatus("已核算");
    when(quoteIngestLogService.createReceived(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(log);
    when(oaFormMapper.selectOne(any())).thenReturn(calculated);

    QuoteIngestResponse response = service.ingest(request);

    assertThat(response.isAccepted()).isFalse();
    assertThat(response.getErrors()).extracting("code").contains("CALCULATED_FORM_LOCKED");
    verify(quoteIngestLogService).markRejected(any(), any(), any());
    verify(oaFormItemMapper, never()).insert(any(OaFormItem.class));
    verify(quoteBomStatusMapper, never()).insert(any(QuoteBomStatus.class));
  }

  private QuoteIngestRequest request(
      String processCode, String externalFormNo, String materialNo, String businessType) {
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(processCode);
    header.setApplyDate("2026-05-11");
    header.setCustomer("客户A");

    QuoteIngestItemRequest item = new QuoteIngestItemRequest();
    item.setSeq(1);
    item.setMaterialNo(materialNo);
    item.setSunlModel("SHF-A");
    item.setBusinessType(businessType);

    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType("EXCEL");
    request.setExternalFormNo(externalFormNo);
    request.setHeader(header);
    request.setItems(List.of(item));
    return request;
  }

  private QuoteIngestLog log(Long id, String idempotencyKey, String payloadHash) {
    QuoteIngestLog log = new QuoteIngestLog();
    log.setId(id);
    log.setRequestId("REQ-" + id);
    log.setIdempotencyKey(idempotencyKey);
    log.setPayloadHash(payloadHash);
    return log;
  }

  private void stubInsertIds() {
    AtomicLong itemIds = new AtomicLong(1000);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, OaForm.class).setId(100L);
              return 1;
            })
        .when(oaFormMapper)
        .insert(any(OaForm.class));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, OaFormItem.class).setId(itemIds.incrementAndGet());
              return 1;
            })
        .when(oaFormItemMapper)
        .insert(any(OaFormItem.class));
  }

  private String sha256(String text) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
