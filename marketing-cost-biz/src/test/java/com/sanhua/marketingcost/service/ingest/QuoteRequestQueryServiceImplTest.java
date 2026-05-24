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
import com.sanhua.marketingcost.entity.OaFormHeaderExtraField;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaFormItemExtraField;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.mapper.OaFormExtraFeeMapper;
import com.sanhua.marketingcost.mapper.OaFormHeaderExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteIngestLogMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestQueryServiceImplTest {
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private OaFormExtraFeeMapper oaFormExtraFeeMapper;
  private OaFormHeaderExtraFieldMapper oaFormHeaderExtraFieldMapper;
  private OaFormItemExtraFieldMapper oaFormItemExtraFieldMapper;
  private QuoteBomStatusMapper quoteBomStatusMapper;
  private QuoteIngestLogMapper quoteIngestLogMapper;
  private U9ProductPackagingTypeResolver productPackagingTypeResolver;
  private QuoteRequestQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    oaFormExtraFeeMapper = mock(OaFormExtraFeeMapper.class);
    oaFormHeaderExtraFieldMapper = mock(OaFormHeaderExtraFieldMapper.class);
    oaFormItemExtraFieldMapper = mock(OaFormItemExtraFieldMapper.class);
    quoteBomStatusMapper = mock(QuoteBomStatusMapper.class);
    quoteIngestLogMapper = mock(QuoteIngestLogMapper.class);
    productPackagingTypeResolver = mock(U9ProductPackagingTypeResolver.class);
    when(productPackagingTypeResolver.resolve(any()))
        .thenReturn(U9ProductPackagingTypeResolver.Result.unknown(null));
    service =
        new QuoteRequestQueryServiceImpl(
            oaFormMapper,
            oaFormItemMapper,
            oaFormExtraFeeMapper,
            oaFormHeaderExtraFieldMapper,
            oaFormItemExtraFieldMapper,
            quoteBomStatusMapper,
            quoteIngestLogMapper,
            productPackagingTypeResolver);
  }

  @Test
  void pageRequestsSupportsOaNoFilter() {
    stubRequestPage(form("OA-T8-001", "FI-SC-020", "CONFIRMED"));
    stubListAggregates(List.of(item(10L, "MAT-1001")), List.of(status(10L, "SYNCED")), log(8L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, "OA-T8", null, null, null);

    assertThat(result.getTotal()).isEqualTo(1);
    assertThat(result.getList().get(0).getOaNo()).isEqualTo("OA-T8-001");
    assertThat(result.getList().get(0).getSourceType()).isEqualTo("EXCEL");
    assertThat(result.getList().get(0).getApplicantUnit()).isEqualTo("申请单位A");
    assertThat(result.getList().get(0).getApplicantDept()).isEqualTo("申请部门A");
    assertThat(result.getList().get(0).getApplicantOffice()).isEqualTo("申请处室A");
    assertThat(result.getList().get(0).getIngestAt()).isNotNull();
    verify(oaFormMapper).selectPage(any(Page.class), any());
  }

  @Test
  void pageRequestsSupportsProcessCodeFilter() {
    stubRequestPage(form("OA-T8-002", "FI-SC-020", "CONFIRMED"));
    stubListAggregates(List.of(item(11L, "MAT-1002")), List.of(status(11L, "SYNCED")), log(9L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, null, "FI-SC-020", null, null);

    assertThat(result.getList()).hasSize(1);
    assertThat(result.getList().get(0).getProcessCode()).isEqualTo("FI-SC-020");
  }

  @Test
  void pageRequestsSupportsSourceTypeFilter() {
    OaForm form = form("OA-T8-003", "FI-SR-005", "CONFIRMED");
    form.setSourceType("WEAVER_OA");
    stubRequestPage(form);
    stubListAggregates(List.of(item(12L, "MAT-1003")), List.of(status(12L, "SYNCED")), log(10L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, null, null, "WEAVER_OA", null);

    assertThat(result.getList().get(0).getSourceType()).isEqualTo("WEAVER_OA");
  }

  @Test
  void pageRequestsSupportsClassificationStatusFilter() {
    stubRequestPage(form("OA-T8-003", "FI-SC-999", "PENDING"));
    stubListAggregates(List.of(item(12L, "MAT-1003")), List.of(status(12L, "NO_BOM")), log(10L));

    PageResult<QuoteRequestListItemResponse> result =
        service.pageRequests(1, 20, null, null, null, "PENDING");

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
    when(oaFormHeaderExtraFieldMapper.selectList(any())).thenReturn(List.of(headerExtraField()));
    when(oaFormItemExtraFieldMapper.selectList(any())).thenReturn(List.of(itemExtraField(13L)));
    when(quoteIngestLogMapper.selectById(8L)).thenReturn(log(8L));
    when(productPackagingTypeResolver.resolve("MAT-1001"))
        .thenReturn(new U9ProductPackagingTypeResolver.Result(
            U9ProductPackagingTypeResolver.PACKAGED_PRODUCT, "120101"));

    QuoteRequestDetailResponse detail = service.getRequestDetail("OA-T8-004");

    assertThat(detail.getOaNo()).isEqualTo("OA-T8-004");
    assertThat(detail.getItems()).hasSize(1);
    assertThat(detail.getSourceType()).isEqualTo("EXCEL");
    assertThat(detail.getApplicantUnit()).isEqualTo("申请单位A");
    assertThat(detail.getSourceCompany()).isEqualTo("来源公司A");
    assertThat(detail.getSourceBusinessDivision()).isEqualTo("事业部A");
    assertThat(detail.getExpenseProductCategory()).isEqualTo("商用直销产品");
    assertThat(detail.getAccountingPeriodMonth()).isEqualTo("2026-05");
    assertThat(detail.getTradeTerms()).isEqualTo("FOB");
    assertThat(detail.getExchangeRate()).isEqualByComparingTo("7.12");
    assertThat(detail.getItems().get(0).getBomStatus().getBomStatus()).isEqualTo("SYNCED");
    assertThat(detail.getItems().get(0).getPackageType()).isEqualTo("纸箱");
    assertThat(detail.getItems().get(0).getShippingFee()).isEqualByComparingTo("2.35");
    assertThat(detail.getItems().get(0).getTotalWithShip()).isEqualByComparingTo("12.35");
    assertThat(detail.getItems().get(0).getBomStatus().getProductPackagingType())
        .isEqualTo("PACKAGED_PRODUCT");
    assertThat(detail.getItems().get(0).getBomStatus().getMainCategoryCode()).isEqualTo("120101");
    assertThat(detail.getExtraFees().get(0).getFeeCode()).isEqualTo("SAMPLE_FEE");
    assertThat(detail.getExtraFees().get(0).getFeeScope()).isEqualTo("ITEM");
    assertThat(detail.getExtraFees().get(0).getSourceFieldPath()).isEqualTo("OA原始表单!G12");
    assertThat(detail.getExtraFields().get(0).getFieldCode()).isEqualTo("FINANCE_NOTE");
    assertThat(detail.getExtraFields().get(0).getOaFormItemId()).isNull();
    assertThat(detail.getExtraFields().get(1).getFieldCode()).isEqualTo("CUSTOMER_COLOR");
    assertThat(detail.getExtraFields().get(1).getOaFormItemId()).isEqualTo(13L);
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
    when(oaFormHeaderExtraFieldMapper.selectList(any())).thenReturn(List.of());
    when(oaFormItemExtraFieldMapper.selectList(any())).thenReturn(List.of());
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
    form.setSourceType("EXCEL");
    form.setSourceSystem("EXCEL_TEMPLATE");
    form.setCustomer("测试客户");
    form.setApplyDate(LocalDate.of(2026, 5, 11));
    form.setAccountingPeriodMonth("2026-05");
    form.setApplicantUnit("申请单位A");
    form.setApplicantDept("申请部门A");
    form.setApplicantOffice("申请处室A");
    form.setSourceCompany("来源公司A");
    form.setSourceBusinessDivision("事业部A");
    form.setExpenseProductCategory("商用直销产品");
    form.setTradeTerms("FOB");
    form.setExchangeRate(new BigDecimal("7.12"));
    form.setCalcStatus("未核算");
    form.setClassificationStatus(classificationStatus);
    form.setIngestLogId(8L);
    form.setBusinessUnitType("COMMERCIAL");
    form.setCreatedAt(LocalDateTime.of(2026, 5, 24, 10, 0));
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
    item.setFirstQuoteFlag(1);
    item.setCertificationRequired(0);
    item.setPackageType("纸箱");
    item.setPackageMethod("托盘");
    item.setPackageComponentCode("PKG-001");
    item.setPackageQty(BigDecimal.ONE);
    item.setShippingFee(new BigDecimal("2.35"));
    item.setSupportQty(BigDecimal.TEN);
    item.setAnnualVolume(BigDecimal.TEN);
    item.setProductStatus("量产");
    item.setScrapRate(new BigDecimal("0.02"));
    item.setUnitLaborCost(new BigDecimal("1.23"));
    item.setTotalWithShip(new BigDecimal("12.35"));
    item.setTotalNoShip(BigDecimal.TEN);
    item.setMaterialCost(new BigDecimal("6.00"));
    item.setLaborCost(new BigDecimal("1.00"));
    item.setManufacturingCost(new BigDecimal("2.00"));
    item.setManagementCost(new BigDecimal("1.00"));
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
    fee.setOaFormItemId(13L);
    fee.setFeeScope("ITEM");
    fee.setBusinessUnitType("COMMERCIAL");
    fee.setFeeCode("SAMPLE_FEE");
    fee.setFeeName("样品费");
    fee.setAmount(BigDecimal.ONE);
    fee.setSourceFieldPath("OA原始表单!G12");
    return fee;
  }

  private OaFormHeaderExtraField headerExtraField() {
    OaFormHeaderExtraField field = new OaFormHeaderExtraField();
    field.setId(200L);
    field.setOaFormId(1L);
    field.setFieldCode("FINANCE_NOTE");
    field.setFieldName("财务备注");
    field.setFieldValue("备注");
    return field;
  }

  private OaFormItemExtraField itemExtraField(Long itemId) {
    OaFormItemExtraField field = new OaFormItemExtraField();
    field.setId(201L);
    field.setOaFormId(1L);
    field.setOaFormItemId(itemId);
    field.setFieldCode("CUSTOMER_COLOR");
    field.setFieldName("客户颜色");
    field.setFieldValue("蓝色");
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
    log.setReceivedAt(LocalDateTime.of(2026, 5, 24, 10, 15));
    return log;
  }
}
