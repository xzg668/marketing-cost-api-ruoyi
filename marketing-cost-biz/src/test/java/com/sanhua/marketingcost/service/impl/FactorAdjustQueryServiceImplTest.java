package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest;
import com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FactorAdjustQueryServiceImplTest {

  private FactorAdjustBatchMapper batchMapper;
  private FactorAdjustPriceMapper priceMapper;
  private FactorIdentityMapper identityMapper;
  private FactorMonthlyPriceMapper monthlyPriceMapper;
  private FactorAdjustQueryServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorAdjustBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorAdjustPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(FactorAdjustBatchMapper.class);
    priceMapper = mock(FactorAdjustPriceMapper.class);
    identityMapper = mock(FactorIdentityMapper.class);
    monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    service = new FactorAdjustQueryServiceImpl(
        batchMapper, priceMapper, identityMapper, monthlyPriceMapper);
  }

  @Test
  @DisplayName("pageBatches：按月份、业务单元、用途、上传人、批次号过滤")
  void pageBatchesFiltersByRequestedFields() {
    Page<FactorAdjustBatch> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(batch(9001L, "alice")));
    when(batchMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    FactorAdjustBatchQueryRequest request = new FactorAdjustBatchQueryRequest();
    request.setPricingMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setAdjustBatchNo("FAB202605");
    request.setUsageScope("REPRICE_ONLY");
    request.setStatus("SUCCESS");
    request.setUploadedBy("alice");
    request.setIncludeAllUploaders(false);

    var response = service.pageBatches(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).hasSize(1);
    assertThat(response.getList().getFirst().getAdjustBatchNo()).contains("FAB202605");
    String sql = capturedBatchSql();
    assertThat(sql).contains(
        "pricing_month", "business_unit_type", "adjust_batch_no",
        "usage_scope", "status", "uploaded_by");
  }

  @Test
  @DisplayName("getBatchDetail：返回批次和价格明细")
  void getBatchDetailReturnsBatchAndPrices() {
    when(batchMapper.selectById(9001L)).thenReturn(batch(9001L, "alice"));
    Page<FactorAdjustPrice> page = new Page<>(1, 1000);
    page.setTotal(1);
    page.setRecords(List.of(adjustPrice(8001L, 9001L)));
    when(priceMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

    var detail = service.getBatchDetail(9001L);

    assertThat(detail).isNotNull();
    assertThat(detail.getBatch().getId()).isEqualTo(9001L);
    assertThat(detail.getPrices()).hasSize(1);
    assertThat(detail.getPrices().getFirst().getAdjustedPrice()).isEqualByComparingTo("19.10");
  }

  @Test
  @DisplayName("pagePrices：按批次、影响因素、状态和关键词过滤")
  void pagePricesFiltersByRequestedFields() {
    Page<FactorAdjustPrice> page = new Page<>(1, 20);
    page.setTotal(1);
    page.setRecords(List.of(adjustPrice(8001L, 9001L)));
    when(priceMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
    FactorAdjustPriceQueryRequest request = new FactorAdjustPriceQueryRequest();
    request.setAdjustBatchId(9001L);
    request.setFactorIdentityId(191L);
    request.setStatus("CHANGED");
    request.setKeyword("锰");

    var response = service.pagePrices(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).hasSize(1);
    String sql = capturedPricePageSql();
    assertThat(sql).contains(
        "adjust_batch_id", "factor_identity_id", "status",
        "factor_seq_no", "factor_name", "short_name", "price_source");
  }

  @Test
  @DisplayName("pageMonthlyPrices：影响因素列表补最近调价批次和调价值")
  void pageMonthlyPricesAddsLatestAdjustInfo() {
    Page<FactorIdentity> identityPage = new Page<>(1, 20);
    identityPage.setTotal(1);
    identityPage.setRecords(List.of(identity(191L)));
    when(identityMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(identityPage);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class)))
        .thenReturn(monthlyPrice(501L, 191L));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(adjustPrice(8001L, 9001L)));
    when(batchMapper.selectById(9001L)).thenReturn(batch(9001L, "alice"));
    FactorMonthlyPriceListQueryRequest request = new FactorMonthlyPriceListQueryRequest();
    request.setPricingMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setKeyword("锰");

    var response = service.pageMonthlyPrices(request);

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).hasSize(1);
    var row = response.getList().getFirst();
    assertThat(row.getDailyEffectivePrice()).isEqualByComparingTo("18.79");
    assertThat(row.getLatestAdjustBatchId()).isEqualTo(9001L);
    assertThat(row.getLatestAdjustBatchNo()).contains("FAB202605");
    assertThat(row.getLatestAdjustUsageScope()).isEqualTo("REPRICE_ONLY");
    assertThat(row.getLatestAdjustPrice()).isEqualByComparingTo("19.10");
    assertThat(row.getLatestAdjustedBy()).isEqualTo("alice");
    assertThat(row.getUnit()).isEqualTo("公斤");
    assertThat(capturedIdentitySql()).contains("business_unit_type", "status", "factor_name");
  }

  private String capturedBatchSql() {
    ArgumentCaptor<Wrapper<FactorAdjustBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(batchMapper).selectPage(any(Page.class), captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private String capturedPricePageSql() {
    ArgumentCaptor<Wrapper<FactorAdjustPrice>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(priceMapper).selectPage(any(Page.class), captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private String capturedIdentitySql() {
    ArgumentCaptor<Wrapper<FactorIdentity>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(identityMapper).selectPage(any(Page.class), captor.capture());
    return captor.getValue().getCustomSqlSegment();
  }

  private FactorAdjustBatch batch(Long id, String uploadedBy) {
    FactorAdjustBatch batch = new FactorAdjustBatch();
    batch.setId(id);
    batch.setAdjustBatchNo("FAB202605160001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setUsageScope("REPRICE_ONLY");
    batch.setStatus("SUCCESS");
    batch.setUploadedBy(uploadedBy);
    batch.setUploadedAt(LocalDateTime.of(2026, 5, 16, 9, 0));
    batch.setDeleted(0);
    return batch;
  }

  private FactorAdjustPrice adjustPrice(Long id, Long batchId) {
    FactorAdjustPrice price = new FactorAdjustPrice();
    price.setId(id);
    price.setAdjustBatchId(batchId);
    price.setFactorIdentityId(191L);
    price.setFactorMonthlyPriceId(501L);
    price.setFactorSeqNo("15");
    price.setFactorName("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
    price.setShortName("1#Mn");
    price.setPriceSource("平均价");
    price.setOriginalPrice(new BigDecimal("18.7929"));
    price.setAdjustedPrice(new BigDecimal("19.10"));
    price.setPriceDelta(new BigDecimal("0.3071"));
    price.setChangeRate(new BigDecimal("1.6341"));
    price.setUnit("公斤");
    price.setStatus("CHANGED");
    price.setDeleted(0);
    return price;
  }

  private FactorIdentity identity(Long id) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType("COMMERCIAL");
    identity.setFactorSeqNo("15");
    identity.setFactorName("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
    identity.setShortName("1#Mn");
    identity.setPriceSource("平均价");
    identity.setStatus("ACTIVE");
    return identity;
  }

  private FactorMonthlyPrice monthlyPrice(Long id, Long identityId) {
    FactorMonthlyPrice price = new FactorMonthlyPrice();
    price.setId(id);
    price.setFactorIdentityId(identityId);
    price.setPriceMonth("2026-05");
    price.setPrice(new BigDecimal("18.79"));
    price.setTaxIncluded(1);
    price.setSourceTag("EXCEL_IMPORT");
    price.setStatus("ACTIVE");
    return price;
  }
}
