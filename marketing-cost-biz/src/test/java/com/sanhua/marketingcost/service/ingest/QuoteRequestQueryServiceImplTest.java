package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogListItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormExtraFee;
import com.sanhua.marketingcost.entity.OaFormExtraField;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.mapper.OaFormExtraFeeMapper;
import com.sanhua.marketingcost.mapper.OaFormExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteIngestLogMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestQueryServiceImplTest {
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private OaFormExtraFeeMapper oaFormExtraFeeMapper;
  private OaFormExtraFieldMapper oaFormExtraFieldMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private QuoteIngestLogMapper quoteIngestLogMapper;
  private QuoteRequestQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    oaFormExtraFeeMapper = mock(OaFormExtraFeeMapper.class);
    oaFormExtraFieldMapper = mock(OaFormExtraFieldMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    quoteIngestLogMapper = mock(QuoteIngestLogMapper.class);
    service =
        new QuoteRequestQueryServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            oaFormExtraFeeMapper,
            oaFormExtraFieldMapper,
            quoteBomStatusMapper,
            quoteIngestLogMapper);
  }

  @Test
  void pageRequestsSupportsOaNoFilter() {
    stubRequestPage(form("OA-T8-001", "FI-SC-020", "CONFIRMED"));
    stubListAggregates(List.of(item(10L, "MAT-1001")), List.of(status(10L, "SYNCED")), log(8L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, "OA-T8", null, null);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
    verify(oaFormMapper).selectPage(any(Page.class), any());
  }

  @Test
  void pageRequestsSupportsProcessCodeFilter() {
    stubRequestPage(form("OA-T8-002", "FI-SC-020", "CONFIRMED"));
    stubListAggregates(List.of(item(11L, "MAT-1002")), List.of(status(11L, "SYNCED")), log(9L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, null, "FI-SC-020", null);

    assertThat(result.getList()).hasSize(1);
    assertThat(result.getList().get(0).getProcessCode()).isEqualTo("FI-SC-020");
  }

  @Test
  void pageRequestsSupportsClassificationStatusFilter() {
    stubRequestPage(form("OA-T8-003", "FI-SC-999", "PENDING"));
    stubListAggregates(List.of(item(12L, "MAT-1003")), List.of(status(12L, "NO_BOM")), log(10L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, null, null, "PENDING");

    assertThat(result.getList().get(0).getClassificationStatus()).isEqualTo("PENDING");
    assertThat(result.getList().get(0).getBomAggregateStatus()).isEqualTo("NO_BOM");
    assertThat(result.getList().get(0).getCalculable()).isFalse();
  }

  @Test
  void detailReturnsHeaderItemsFeesBomStatusAndIngestSummary() {
    OaForm form = form("OA-T8-004", "FI-SC-020", "CONFIRMED");
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item(13L, "MAT-1004")));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status(13L, "SYNCED")));
    when(oaFormExtraFeeMapper.selectList(any())).thenReturn(List.of(extraFee()));
    when(oaFormExtraFieldMapper.selectList(any())).thenReturn(List.of(extraField()));
    when(quoteIngestLogMapper.selectById(8L)).thenReturn(log(8L));

    QuoteRequestDetailResponse detail = service.getRequestDetail("OA-T8-004");

    assertThat(detail.getOaNo()).isEqualTo("OA-T8-004");
    assertThat(detail.getItems()).hasSize(1);
    assertThat(detail.getItems().get(0).getBomStatus().getBomStatus()).isEqualTo("SYNCED");
    assertThat(detail.getExtraFees().get(0).getFeeCode()).isEqualTo("SAMPLE_FEE");
    assertThat(detail.getExtraFields().get(0).getFieldCode()).isEqualTo("FINANCE_NOTE");
    assertThat(detail.getIngestLog().getPayloadSummary()).contains("raw");
    assertThat(detail.getIngestLog().getNormalizedSummary()).contains("normalized");
  }

  @Test
  void pendingClassificationCanConfirm() {
    OaForm form = form("OA-T8-005", "FI-SC-999", "PENDING");
    form.setQuoteScenario(null);
    when(oaFormMapper.selectOne(any())).thenReturn(form);
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item(14L, "MAT-1005")));
    when(quoteBomStatusMapper.selectList(any())).thenReturn(List.of(status(14L, "SYNCED")));
    when(oaFormExtraFeeMapper.selectList(any())).thenReturn(List.of());
    when(oaFormExtraFieldMapper.selectList(any())).thenReturn(List.of());
    QuoteIngestLog log = log(8L);
    log.setIngestStatus("CLASSIFY_PENDING");
    when(quoteIngestLogMapper.selectById(8L)).thenReturn(log);
    QuoteRequestConfirmClassificationRequest request = new QuoteRequestConfirmClassificationRequest();
    request.setQuoteScenario("NEW_PRODUCT");
    request.setBusinessUnitType("COMMERCIAL");

    QuoteRequestDetailResponse detail = service.confirmClassification("OA-T8-005", request);

    assertThat(detail.getClassificationStatus()).isEqualTo("CONFIRMED");
    assertThat(detail.getQuoteScenario()).isEqualTo("NEW_PRODUCT");
    assertThat(log.getIngestStatus()).isEqualTo("IMPORTED");
    verify(oaFormMapper).updateById(form);
    verify(oaFormItemMapper).update(any(), any());
    verify(quoteIngestLogMapper).updateById(log);
  }

  @Test
  void logsExposeRawPayloadAndNormalizedPayload() {
    QuoteIngestLog log = log(15L);
    Page<QuoteIngestLog> page = new Page<>(1, 20);
    page.setRecords(List.of(log));
    page.setTotal(1);
    when(quoteIngestLogMapper.selectPage(any(Page.class), any())).thenReturn(page);
    when(quoteIngestLogMapper.selectById(15L)).thenReturn(log);

    PageResult<QuoteIngestLogListItemResponse> logs =
        service.pageLogs(1, 20, "OA-T8", "OA", "IMPORTED");
    QuoteIngestLogDetailResponse detail = service.getLogDetail(15L);

    assertThat(logs.getTotal()).isEqualTo(1);
    assertThat(logs.getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
    assertThat(detail.getPayloadJson()).contains("raw");
    assertThat(detail.getNormalizedJson()).contains("normalized");
  }

  @Test
  void confirmClassificationRequiresScenarioWhenFormHasNone() {
    OaForm form = form("OA-T8-006", "FI-SC-999", "PENDING");
    form.setQuoteScenario(null);
    when(oaFormMapper.selectOne(any())).thenReturn(form);

    assertThatThrownBy(() -> service.confirmClassification("OA-T8-006", null))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("报价场景不能为空");
  }

  private void stubRequestPage(OaForm form) {
    Page<OaForm> page = new Page<>(1, 20);
    page.setRecords(List.of(form));
    page.setTotal(1);
    when(oaFormMapper.selectPage(any(Page.class), any())).thenReturn(page);
  }

  private void stubListAggregates(
      List<OaFormItem> items, List<QuoteBomStatus> statuses, QuoteIngestLog log) {
    when(oaFormItemMapper.selectList(any())).thenReturn(items);
    when(quoteBomStatusMapper.selectList(any())).thenReturn(new ArrayList<>(statuses));
    when(quoteIngestLogMapper.selectById(8L)).thenReturn(log);
  }

  private OaForm form(String oaNo, String processCode, String classificationStatus) {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo(oaNo);
    form.setProcessCode(processCode);
    form.setProcessName("流程名称");
    form.setQuoteScenario("DIRECT_SALE");
    form.setCustomer("测试客户");
    form.setApplyDate(LocalDate.of(2026, 5, 11));
    form.setCalcStatus("未核算");
    form.setClassificationStatus(classificationStatus);
    form.setIngestLogId(8L);
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private OaFormItem item(Long id, String materialNo) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(1L);
    item.setSeq(1);
    item.setProductName("产品");
    item.setMaterialNo(materialNo);
    item.setSunlModel("SHF-A");
    item.setAnnualVolume(BigDecimal.TEN);
    item.setClassificationStatus("CONFIRMED");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private QuoteBomStatus status(Long itemId, String bomStatus) {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(itemId + 100);
    status.setOaFormItemId(itemId);
    status.setOaNo("OA-T8-001");
    status.setProductCode("MAT-1001");
    status.setProductModel("SHF-A");
    status.setBomStatus(bomStatus);
    status.setBomSource("U9");
    return status;
  }

  private OaFormExtraFee extraFee() {
    OaFormExtraFee fee = new OaFormExtraFee();
    fee.setId(100L);
    fee.setOaFormId(1L);
    fee.setFeeCode("SAMPLE_FEE");
    fee.setFeeName("样品费");
    fee.setAmount(BigDecimal.ONE);
    return fee;
  }

  private OaFormExtraField extraField() {
    OaFormExtraField field = new OaFormExtraField();
    field.setId(200L);
    field.setOaFormId(1L);
    field.setFieldCode("FINANCE_NOTE");
    field.setFieldName("财务备注");
    field.setFieldValue("备注");
    return field;
  }

  private QuoteIngestLog log(Long id) {
    QuoteIngestLog log = new QuoteIngestLog();
    log.setId(id);
    log.setRequestId("REQ-T8");
    log.setIdempotencyKey("IDEM-T8");
    log.setSourceType("OA");
    log.setSourceSystem("OA");
    log.setExternalFormNo("EXT-T8");
    log.setOaNo("OA-T8-001");
    log.setProcessCode("FI-SC-020");
    log.setQuoteScenario("DIRECT_SALE");
    log.setIngestStatus("IMPORTED");
    log.setClassificationStatus("CONFIRMED");
    log.setPayloadJson("{\"raw\":true}");
    log.setNormalizedJson("{\"normalized\":true}");
    return log;
  }
}
