package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareQueryServiceImplTest {

  private PricePrepareBatchMapper batchMapper;
  private OaFormMapper oaFormMapper;
  private OaFormItemMapper oaFormItemMapper;
  private PricePrepareItemMapper itemMapper;
  private PricePrepareGapMapper gapMapper;
  private PricePrepareQueryServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareGap.class);
    TableInfoHelper.initTableInfo(assistant, OaForm.class);
    TableInfoHelper.initTableInfo(assistant, OaFormItem.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(PricePrepareBatchMapper.class);
    oaFormMapper = mock(OaFormMapper.class);
    oaFormItemMapper = mock(OaFormItemMapper.class);
    itemMapper = mock(PricePrepareItemMapper.class);
    gapMapper = mock(PricePrepareGapMapper.class);
    service =
        new PricePrepareQueryServiceImpl(
            oaFormMapper, oaFormItemMapper, batchMapper, itemMapper, gapMapper);
  }

  @Test
  @DisplayName("批次分页：过滤 OA、期间和状态")
  void pageBatchesUsesFilters() {
    when(batchMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PricePrepareBatch> page = invocation.getArgument(0);
      page.setTotal(1);
      page.setRecords(List.of(new PricePrepareBatch()));
      return page;
    });
    PricePrepareBatchQueryRequest request = new PricePrepareBatchQueryRequest();
    request.setOaNo(" OA-001 ");
    request.setPeriodMonth(" 2026-05 ");
    request.setStatus(" SUCCESS ");
    request.setPage(2);
    request.setPageSize(600);

    PricePrepareBatchPageResponse response = service.pageBatches(request);

    assertThat(response.getTotal()).isEqualTo(1);
    ArgumentCaptor<Page<PricePrepareBatch>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<LambdaQueryWrapper<PricePrepareBatch>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(batchMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(500);
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("oa_no", "period_month", "status", "ORDER BY");
    assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
        .contains("OA-001", "2026-05", "SUCCESS");
  }

  @Test
  @DisplayName("明细分页：默认分页并过滤料号类型和状态")
  void pageItemsUsesFilters() {
    when(itemMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PricePrepareItem> page = invocation.getArgument(0);
      page.setTotal(0);
      page.setRecords(List.of());
      return page;
    });
    PricePrepareItemQueryRequest request = new PricePrepareItemQueryRequest();
    request.setPrepareNo("PPR-1");
    request.setTopProductCode("1079900000536");
    request.setMaterialCode("9830000026238");
    request.setItemType("PACKAGE_COMPONENT");
    request.setStatus("READY");

    PricePrepareItemPageResponse response = service.pageItems(request);

    assertThat(response.getTotal()).isZero();
    ArgumentCaptor<Page<PricePrepareItem>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<LambdaQueryWrapper<PricePrepareItem>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(itemMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("prepare_no", "top_product_code", "material_code", "item_type", "status", "ORDER BY");
  }

  @Test
  @DisplayName("OA汇总：按顶级产品汇总后判断 OA 是否全部就绪")
  void pageOaSummariesAggregatesTopProducts() {
    when(itemMapper.selectMaps(any())).thenReturn(List.of(
        Map.of(
            "oa_no", "OA-001",
            "top_product_code", "TOP-1",
            "total_count", 2,
            "ready_count", 2,
            "failed_count", 0,
            "updated_at", LocalDateTime.of(2026, 5, 21, 10, 0)),
        Map.of(
            "oa_no", "OA-001",
            "top_product_code", "TOP-2",
            "total_count", 3,
            "ready_count", 2,
            "failed_count", 0,
            "updated_at", LocalDateTime.of(2026, 5, 21, 11, 0))));
    when(gapMapper.selectMaps(any())).thenReturn(List.of(
        Map.of("oa_no", "OA-001", "top_product_code", "TOP-2", "gap_count", 1)));
    PricePrepareOaSummaryQueryRequest request = new PricePrepareOaSummaryQueryRequest();
    request.setOaNo("OA-001");

    PricePrepareOaSummaryPageResponse response = service.pageOaSummaries(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getTopProductCount()).isEqualTo(2);
    assertThat(response.getRecords().get(0).getReadyTopProductCount()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getGapCount()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getStatus()).isEqualTo("PARTIAL");
  }

  @Test
  @DisplayName("顶级产品汇总：显式查询无结果时返回未准备")
  void pageTopProductSummariesReturnsNotPreparedForExplicitEmptyScope() {
    when(itemMapper.selectMaps(any())).thenReturn(List.of());
    when(gapMapper.selectMaps(any())).thenReturn(List.of());
    PricePrepareTopProductSummaryQueryRequest request = new PricePrepareTopProductSummaryQueryRequest();
    request.setOaNo("OA-001");
    request.setTopProductCode("TOP-1");

    PricePrepareTopProductSummaryPageResponse response = service.pageTopProductSummaries(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getStatus()).isEqualTo("NOT_PREPARED");
  }

  @Test
  @DisplayName("候选查询：普通范围没有关键词时不默认返回全量")
  void pageCandidatesMineWithoutKeywordReturnsEmpty() {
    PricePrepareCandidateQueryRequest request = new PricePrepareCandidateQueryRequest();
    request.setOwnerScope("MINE");

    PricePrepareCandidatePageResponse response = service.pageCandidates(request);

    assertThat(response.getTotal()).isZero();
    assertThat(response.getRecords()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(oaFormMapper, oaFormItemMapper);
  }

  @Test
  @DisplayName("候选查询：无价格准备汇总的 OA+成品返回未准备")
  void pageCandidatesReturnsNotPreparedWhenNoSummary() {
    OaForm form = oaForm(1L, "OA-001", "客户A", "未核算");
    OaFormItem item = oaFormItem(10L, 1L, "TOP-A", "产品A");
    when(oaFormMapper.selectList(any())).thenReturn(List.of(form));
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(itemMapper.selectMaps(any())).thenReturn(List.of());
    when(gapMapper.selectMaps(any())).thenReturn(List.of());
    PricePrepareCandidateQueryRequest request = new PricePrepareCandidateQueryRequest();
    request.setOwnerScope("ALL");

    PricePrepareCandidatePageResponse response = service.pageCandidates(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getOaNo()).isEqualTo("OA-001");
    assertThat(response.getRecords().get(0).getTopProductCode()).isEqualTo("TOP-A");
    assertThat(response.getRecords().get(0).getPrepareStatus()).isEqualTo("NOT_PREPARED");
    assertThat(response.getRecords().get(0).getProductName()).isEqualTo("产品A");
  }

  @Test
  @DisplayName("候选查询：全量范围不默认限定未核算，已核算 OA 也能查询")
  void pageCandidatesAllWithoutCalcStatusDoesNotFilterCalculatedForms() {
    OaForm form = oaForm(1L, "OA-CALCED", "客户A", "已核算");
    OaFormItem item = oaFormItem(10L, 1L, "TOP-CALCED", "产品A");
    when(oaFormMapper.selectList(any())).thenReturn(List.of(form));
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(itemMapper.selectMaps(any())).thenReturn(List.of(
        Map.of(
            "oa_no", "OA-CALCED",
            "top_product_code", "TOP-CALCED",
            "total_count", 2,
            "ready_count", 1,
            "failed_count", 0,
            "updated_at", LocalDateTime.of(2026, 5, 21, 10, 0))));
    when(gapMapper.selectMaps(any())).thenReturn(List.of(
        Map.of("oa_no", "OA-CALCED", "top_product_code", "TOP-CALCED", "gap_count", 1)));
    PricePrepareCandidateQueryRequest request = new PricePrepareCandidateQueryRequest();
    request.setOwnerScope("ALL");
    request.setCalcStatus("");

    PricePrepareCandidatePageResponse response = service.pageCandidates(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords().get(0).getOaNo()).isEqualTo("OA-CALCED");
    ArgumentCaptor<LambdaQueryWrapper<OaForm>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(oaFormMapper).selectList(queryCaptor.capture());
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .doesNotContain("calc_status");
  }

  @Test
  @DisplayName("候选查询：默认 onlyPending 不返回 READY 行")
  void pageCandidatesDefaultOnlyPendingFiltersReadyRows() {
    OaForm form = oaForm(1L, "OA-READY", "客户A", "未核算");
    OaFormItem item = oaFormItem(10L, 1L, "TOP-READY", "产品A");
    when(oaFormMapper.selectList(any())).thenReturn(List.of(form));
    when(oaFormItemMapper.selectList(any())).thenReturn(List.of(item));
    when(itemMapper.selectMaps(any())).thenReturn(List.of(
        Map.of(
            "oa_no", "OA-READY",
            "top_product_code", "TOP-READY",
            "total_count", 2,
            "ready_count", 2,
            "failed_count", 0,
            "updated_at", LocalDateTime.of(2026, 5, 21, 10, 0))));
    when(gapMapper.selectMaps(any())).thenReturn(List.of());
    PricePrepareCandidateQueryRequest request = new PricePrepareCandidateQueryRequest();
    request.setOwnerScope("ALL");

    PricePrepareCandidatePageResponse response = service.pageCandidates(request);

    assertThat(response.getTotal()).isZero();
    assertThat(response.getRecords()).isEmpty();
  }

  @Test
  @DisplayName("缺口分页：过滤真正缺数据料号和 OA 推送状态")
  void pageGapsUsesFilters() {
    when(gapMapper.selectPage(any(), any())).thenAnswer(invocation -> {
      Page<PricePrepareGap> page = invocation.getArgument(0);
      page.setTotal(0);
      page.setRecords(List.of());
      return page;
    });
    PricePrepareGapQueryRequest request = new PricePrepareGapQueryRequest();
    request.setOaNo("OA-001");
    request.setGapMaterialCode("250011491");
    request.setGapType("MISSING_PRICE");
    request.setItemType("PACKAGE_COMPONENT");
    request.setOaPushStatus("PENDING");

    PricePrepareGapPageResponse response = service.pageGaps(request);

    assertThat(response.getTotal()).isZero();
    ArgumentCaptor<LambdaQueryWrapper<PricePrepareGap>> queryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(gapMapper).selectPage(any(Page.class), queryCaptor.capture());
    assertThat(((AbstractWrapper<?, ?, ?>) queryCaptor.getValue()).getSqlSegment())
        .contains("oa_no", "gap_material_code", "gap_type", "item_type", "oa_push_status", "ORDER BY");
  }

  private OaForm oaForm(Long id, String oaNo, String customer, String calcStatus) {
    OaForm form = new OaForm();
    form.setId(id);
    form.setOaNo(oaNo);
    form.setCustomer(customer);
    form.setCalcStatus(calcStatus);
    form.setSaleLink("报价员A");
    form.setApplyDate(LocalDate.of(2026, 5, 21));
    form.setUpdatedAt(LocalDateTime.of(2026, 5, 21, 9, 0));
    return form;
  }

  private OaFormItem oaFormItem(Long id, Long oaFormId, String materialNo, String productName) {
    OaFormItem item = new OaFormItem();
    item.setId(id);
    item.setOaFormId(oaFormId);
    item.setMaterialNo(materialNo);
    item.setProductName(productName);
    item.setSunlModel("SUNL-A");
    item.setUpdatedAt(LocalDateTime.of(2026, 5, 21, 9, 30));
    return item;
  }
}
