package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceConstants;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class FinanceQuoteBasePriceServiceImplTest {

  private FinanceBasePriceMapper financeBasePriceMapper;
  private SysOperationLogMapper operationLogMapper;
  private QuoteCostRunVersionInvalidationService versionInvalidationService;
  private FinanceQuoteBasePriceServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
    TableInfoHelper.initTableInfo(assistant, SysOperationLog.class);
  }

  @BeforeEach
  void setUp() {
    financeBasePriceMapper = org.mockito.Mockito.mock(FinanceBasePriceMapper.class);
    operationLogMapper = org.mockito.Mockito.mock(SysOperationLogMapper.class);
    versionInvalidationService =
        org.mockito.Mockito.mock(QuoteCostRunVersionInvalidationService.class);
    service = new FinanceQuoteBasePriceServiceImpl(
        financeBasePriceMapper,
        operationLogMapper,
        new ObjectMapper(),
        versionInvalidationService);
    authenticate("finance.user", "COMMERCIAL");
    when(operationLogMapper.insert(any(SysOperationLog.class))).thenReturn(1);
    AtomicLong ids = new AtomicLong(100);
    when(financeBasePriceMapper.insert(any(FinanceBasePrice.class))).thenAnswer(invocation -> {
      FinanceBasePrice entity = invocation.getArgument(0);
      entity.setId(ids.incrementAndGet());
      return 1;
    });
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("精确查询固定使用Cu、月份、财务报价来源和当前业务单元四个条件")
  void getRequiredUsesFourExactFilters() {
    when(financeBasePriceMapper.selectOne(any(Wrapper.class)))
        .thenReturn(financeRow(7L, "2026-07", "90", "COMMERCIAL"));

    FinanceBasePrice result = service.getRequired("2026-07");

    assertThat(result.getPrice()).isEqualByComparingTo("90");
    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor = wrapperCaptor();
    verify(financeBasePriceMapper).selectOne(captor.capture());
    AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
    String sql = wrapper.getSqlSegment().toLowerCase();
    assertThat(sql).contains("factor_code", "price_month", "price_source", "business_unit_type");
    assertThat(wrapper.getParamNameValuePairs().values())
        .contains(FinanceQuoteBasePriceConstants.FACTOR_CODE, "2026-07",
            FinanceQuoteBasePriceConstants.PRICE_SOURCE, "COMMERCIAL");
  }

  @Test
  @DisplayName("列表从现有操作日志补齐最近修改人、原因和时间")
  void listEnrichesLatestAuditSummary() {
    FinanceBasePrice row = financeRow(7L, "2026-07", "90", "COMMERCIAL");
    when(financeBasePriceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
    SysOperationLog older = auditLog(7L, "older", "初始化", 1);
    SysOperationLog latest = auditLog(7L, "finance.latest", "财务最新调整", 2);
    when(operationLogMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(latest, older));

    var result = service.list("2026-07", "2026-07");

    assertThat(result).singleElement().satisfies(response -> {
      assertThat(response.lastModifiedBy()).isEqualTo("finance.latest");
      assertThat(response.lastChangeReason()).isEqualTo("财务最新调整");
      assertThat(response.lastModifiedAt()).isEqualTo(latest.getOperTime());
    });
  }

  @Test
  @DisplayName("缺少当月专用配置明确阻断且不查询市场价、SMM或上月")
  void missingMonthBlocksWithoutFallback() {
    assertThatThrownBy(() -> service.getRequired("2026-08"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未维护2026-08财务报价Cu基准，请先由财务初始化或调整后再试算。");

    verify(financeBasePriceMapper, times(1)).selectOne(any(Wrapper.class));
    verify(financeBasePriceMapper, never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("初始化6个月：90000元每吨保存为90元每公斤并逐月审计")
  void initializeSixMonthsConvertsAndAudits() {
    var result = service.initialize(
        new FinanceQuoteBasePriceInitializeRequest("2026-07", "2026-12",
            new BigDecimal("90000")));

    assertThat(result.createdCount()).isEqualTo(6);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.createdMonths()).containsExactly(
        "2026-07", "2026-08", "2026-09", "2026-10", "2026-11", "2026-12");
    ArgumentCaptor<FinanceBasePrice> rowCaptor = ArgumentCaptor.forClass(FinanceBasePrice.class);
    verify(financeBasePriceMapper, times(6)).insert(rowCaptor.capture());
    assertThat(rowCaptor.getAllValues()).allSatisfy(row -> {
      assertThat(row.getPrice()).isEqualByComparingTo("90.000000");
      assertThat(row.getFactorCode()).isEqualTo("Cu");
      assertThat(row.getPriceSource()).isEqualTo("财务报价基准");
      assertThat(row.getShortName()).isEqualTo("报价Cu基准");
      assertThat(row.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    });
    ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
    verify(operationLogMapper, times(6)).insert(logCaptor.capture());
    assertThat(logCaptor.getAllValues()).allSatisfy(log -> {
      assertThat(log.getOperName()).isEqualTo("finance.user");
      assertThat(log.getBeforeData()).isNull();
      assertThat(log.getAfterData()).contains("\"pricePerKg\":90.000000");
      assertThat(log.getOperParam()).contains("批量初始化财务报价Cu基准");
    });
  }

  @Test
  @DisplayName("初始化12个月完整覆盖月份范围")
  void initializeTwelveMonths() {
    var result = service.initialize(
        new FinanceQuoteBasePriceInitializeRequest("2027-01", "2027-12",
            new BigDecimal("90000")));

    assertThat(result.createdCount()).isEqualTo(12);
    assertThat(result.records()).hasSize(12);
    assertThat(result.createdMonths()).startsWith("2027-01").endsWith("2027-12");
    verify(financeBasePriceMapper, times(12)).insert(any(FinanceBasePrice.class));
  }

  @Test
  @DisplayName("已有月份默认跳过，不覆盖原价格也不伪造操作日志")
  void initializeSkipsExistingMonth() {
    FinanceBasePrice existing = financeRow(8L, "2026-07", "88", "COMMERCIAL");
    when(financeBasePriceMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    var result = service.initialize(
        new FinanceQuoteBasePriceInitializeRequest("2026-07", "2026-07",
            new BigDecimal("90000")));

    assertThat(result.createdCount()).isZero();
    assertThat(result.skippedMonths()).containsExactly("2026-07");
    assertThat(result.records().getFirst().pricePerKg()).isEqualByComparingTo("88");
    verify(financeBasePriceMapper, never()).insert(any(FinanceBasePrice.class));
    verify(financeBasePriceMapper, never()).updateById(any(FinanceBasePrice.class));
    verify(operationLogMapper, never()).insert(any(SysOperationLog.class));
  }

  @Test
  @DisplayName("调整指定月份必须记录修改前后值、操作人和原因")
  void adjustWritesCompleteAudit() {
    FinanceBasePrice existing = financeRow(9L, "2026-09", "90", "COMMERCIAL");
    when(financeBasePriceMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(financeBasePriceMapper.updateById(existing)).thenReturn(1);

    var response = service.adjust(
        9L, new FinanceQuoteBasePriceAdjustRequest(new BigDecimal("95000"), "财务通知9月调价"));

    assertThat(response.pricePerKg()).isEqualByComparingTo("95.000000");
    verify(financeBasePriceMapper).updateById(existing);
    ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
    verify(operationLogMapper).insert(captor.capture());
    SysOperationLog log = captor.getValue();
    assertThat(log.getOperName()).isEqualTo("finance.user");
    assertThat(log.getBeforeData()).contains("\"pricePerKg\":90");
    assertThat(log.getAfterData()).contains("\"pricePerKg\":95.000000");
    assertThat(log.getOperParam()).contains("财务通知9月调价");
    assertThat(log.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    verify(versionInvalidationService).invalidateByFinanceCu("2026-09", "COMMERCIAL");
  }

  @Test
  @DisplayName("调整值与原值相同时保留审计但不误判试算版本失效")
  void adjustSamePriceDoesNotInvalidateTrialVersions() {
    FinanceBasePrice existing = financeRow(9L, "2026-09", "95", "COMMERCIAL");
    when(financeBasePriceMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(financeBasePriceMapper.updateById(existing)).thenReturn(1);

    service.adjust(
        9L, new FinanceQuoteBasePriceAdjustRequest(new BigDecimal("95000"), "复核无变化"));

    verify(operationLogMapper).insert(any(SysOperationLog.class));
    verify(versionInvalidationService, never()).invalidateByFinanceCu(any(), any());
  }

  @Test
  @DisplayName("负数、零和超过一百万元每吨的价格全部拒绝")
  void rejectsInvalidPriceBoundaries() {
    List<BigDecimal> invalid = List.of(
        new BigDecimal("-1"), BigDecimal.ZERO, new BigDecimal("1000000.000001"));

    for (BigDecimal price : invalid) {
      assertThatThrownBy(() -> service.initialize(
          new FinanceQuoteBasePriceInitializeRequest("2026-07", "2026-07", price)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verify(financeBasePriceMapper, never()).insert(any(FinanceBasePrice.class));
  }

  @Test
  @DisplayName("非法月份、倒置月份范围和空调整原因全部拒绝")
  void rejectsInvalidMonthsAndBlankReason() {
    assertThatThrownBy(() -> service.initialize(
        new FinanceQuoteBasePriceInitializeRequest("2026-7", "2026-12",
            new BigDecimal("90000"))))
        .hasMessageContaining("yyyy-MM");
    assertThatThrownBy(() -> service.initialize(
        new FinanceQuoteBasePriceInitializeRequest("2026-12", "2026-07",
            new BigDecimal("90000"))))
        .hasMessage("endMonth不能早于startMonth");
    assertThatThrownBy(() -> service.adjust(
        1L, new FinanceQuoteBasePriceAdjustRequest(new BigDecimal("90000"), " ")))
        .hasMessage("调整原因不能为空");
  }

  @Test
  @DisplayName("调整查询固定携带当前BU，跨BU记录不可见")
  void adjustIsIsolatedByCurrentBusinessUnit() {
    authenticate("household.finance", "HOUSEHOLD");

    assertThatThrownBy(() -> service.adjust(
        9L, new FinanceQuoteBasePriceAdjustRequest(new BigDecimal("95000"), "家用调整")))
        .hasMessageContaining("当前业务单元");

    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor = wrapperCaptor();
    verify(financeBasePriceMapper).selectOne(captor.capture());
    AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
    wrapper.getSqlSegment();
    assertThat(wrapper.getParamNameValuePairs().values()).contains("HOUSEHOLD");
    verify(financeBasePriceMapper, never()).updateById(any(FinanceBasePrice.class));
  }

  @Test
  @DisplayName("缺少当前业务单元上下文时不允许读写")
  void requiresBusinessUnitContext() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(() -> service.getRequired("2026-07"))
        .hasMessage("当前业务单元不能为空");
    verify(financeBasePriceMapper, never()).selectOne(any(Wrapper.class));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private ArgumentCaptor<Wrapper<FinanceBasePrice>> wrapperCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
  }

  private FinanceBasePrice financeRow(Long id, String month, String price, String bu) {
    FinanceBasePrice row = new FinanceBasePrice();
    row.setId(id);
    row.setPriceMonth(month);
    row.setFactorName(FinanceQuoteBasePriceConstants.FACTOR_NAME);
    row.setShortName(FinanceQuoteBasePriceConstants.SHORT_NAME);
    row.setFactorCode(FinanceQuoteBasePriceConstants.FACTOR_CODE);
    row.setPriceSource(FinanceQuoteBasePriceConstants.PRICE_SOURCE);
    row.setPrice(new BigDecimal(price));
    row.setUnit(FinanceQuoteBasePriceConstants.UNIT);
    row.setLinkType(FinanceQuoteBasePriceConstants.LINK_TYPE);
    row.setBusinessUnitType(bu);
    return row;
  }

  private SysOperationLog auditLog(Long targetId, String operator, String reason, int minute) {
    SysOperationLog log = new SysOperationLog();
    log.setOperId((long) minute);
    log.setTitle("财务Cu报价基准维护");
    log.setTargetId(String.valueOf(targetId));
    log.setOperName(operator);
    log.setOperParam("{\"changeReason\":\"" + reason + "\"}");
    log.setOperTime(java.time.LocalDateTime.of(2026, 7, 1, 10, minute));
    log.setBusinessUnitType("COMMERCIAL");
    log.setStatus(0);
    return log;
  }

  private void authenticate(String username, String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(username, "n/a", List.of());
    authentication.setDetails(Map.of(
        BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
