package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

class MakePartPriceCalcResolverTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MakePartPriceCalcRow.class);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("CMPPC-02：严格按当前 OA、月份、业务单元、料号读取完整 OK 生成结果")
  void resolvesCurrentOaMonthBusinessUnitCompleteCalcRow() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    MakePartPriceCalcRow row = row("BATCH-1", "MAKE-001", "OK", true, "12.34000000", null);
    row.setOaNo("OA-MAKE");
    row.setPricingMonth(currentMonth());
    row.setBusinessUnitType("COMMERCIAL");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

    PriceResolveResult result = resolver.resolve("OA-MAKE", part("MAKE-001"), null);

    assertThat(result.unitPrice()).isEqualByComparingTo("12.34000000");
    assertThat(result.priceSource()).isEqualTo(PriceTypeEnum.MAKE.getDbText());
    assertThat(result.remark()).contains("取自制造件价格生成结果", "批次=BATCH-1", "价格月份=" + currentMonth());

    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains(
            "parent_material_no",
            "oa_no",
            "pricing_month",
            "business_unit_type",
            "status",
            "price_complete",
            "parent_total_cost_price IS NOT NULL")
        .contains("ORDER BY", "created_at", "id", "DESC")
        .contains("LIMIT 1");
  }

  @Test
  @DisplayName("CMPPC-02：缺生成结果时返回带 OA、月份、料号的业务缺价原因")
  void missingCalcRowReturnsBusinessMiss() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    PriceResolveResult result = resolver.resolve("OA-MAKE", part("MAKE-MISSING"), null);

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark())
        .contains("缺制造件价格生成结果")
        .contains("OA=OA-MAKE")
        .contains("月份=" + currentMonth())
        .contains("料号=MAKE-MISSING")
        .contains("制造件价格生成");
  }

  @Test
  @DisplayName("CMPPC-02：oaNo 为空时不回退空 OA 通用结果")
  void blankOaDoesNotFallbackToGenericResult() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);

    PriceResolveResult result = resolver.resolve(null, part("MAKE-GENERIC"), null);

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark())
        .contains("缺制造件价格生成结果")
        .contains("月份=" + currentMonth())
        .contains("料号=MAKE-GENERIC");
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("CMPPC-02：parent_total_cost_price 为空时不按明细 cost_price 求和")
  void parentTotalMissingDoesNotSumCostPrice() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    MakePartPriceCalcRow latest = row("BATCH-SUM", "MAKE-SUM", "OK", true, null, "1.11000000");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(latest));

    PriceResolveResult result = resolver.resolve("OA-MAKE", part("MAKE-SUM"), null);

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark()).contains("制造件价格生成结果缺少价格：MAKE-SUM");
    verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("CMPPC-02：查询条件固定为当前 OA，不回退其他 OA")
  void queryUsesExactOaNo() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    resolver.resolve("OA-CURRENT", part("MAKE-001"), null);

    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("oa_no")
        .doesNotContain("IS NULL")
        .doesNotContain(" OR ");
  }

  @Test
  @DisplayName("CMPPC-02：查询条件固定为系统当前月份，不回退历史月份")
  void queryUsesCurrentMonth() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    resolver.resolve("OA-MAKE", part("MAKE-HISTORY"), null);

    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains("pricing_month");
    assertThat(paramValues(captor.getValue())).contains(currentMonth());
  }

  @Test
  @DisplayName("CMPPC-02：查询条件固定为当前业务单元，不回退其他业务单元")
  void queryUsesCurrentBusinessUnit() {
    setBusinessUnit("COMMERCIAL");
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    resolver.resolve("OA-MAKE", part("MAKE-BU"), null);

    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains("business_unit_type");
    assertThat(paramValues(captor.getValue())).contains("COMMERCIAL");
  }

  @Test
  @DisplayName("T21：月度调价制造件结果按 price_as_of_time 严格命中")
  void monthlyResolveUsesPriceAsOfTime() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 26, 10, 30);
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    MakePartPriceCalcRow row = row("BATCH-MONTHLY", "MAKE-001", "OK", true, "18.88000000", null);
    row.setPriceAsOfTime(priceAsOfTime);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
    CostRunContext context = CostRunContext.monthlyReprice(
        "2026-05",
        7L,
        "MR-001",
        "COMMERCIAL",
        priceAsOfTime,
        CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        "OA-MAKE",
        null,
        "TOP-001",
        null,
        null,
        "OBJ-1");

    PriceResolveResult result = resolver.resolve("OA-MAKE", part("MAKE-001"), null, context);

    assertThat(result.unitPrice()).isEqualByComparingTo("18.88000000");
    assertThat(result.remark()).contains("取价时点=2026-05-26T10:30");
    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains("price_as_of_time");
    assertThat(paramValues(captor.getValue())).contains("2026-05", "COMMERCIAL", priceAsOfTime);
  }

  @Test
  @DisplayName("普通报价制造件不按 price_as_of_time 严格过滤")
  void quoteResolveIgnoresPriceAsOfTime() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 6, 17, 16, 0);
    MakePartPriceCalcRowMapper mapper = Mockito.mock(MakePartPriceCalcRowMapper.class);
    MakePartPriceCalcResolver resolver = new MakePartPriceCalcResolver(mapper);
    MakePartPriceCalcRow row = row("BATCH-QUOTE", "MAKE-001", "OK", true, "13.77000000", null);
    row.setPriceAsOfTime(LocalDateTime.of(2026, 6, 30, 23, 59, 59));
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
    CostRunContext context =
        CostRunContext.quote(
            "OA-MAKE",
            10L,
            "TOP-001",
            null,
            null,
            "COMMERCIAL",
            "2026-06",
            priceAsOfTime,
            "OBJ-1");

    PriceResolveResult result = resolver.resolve("OA-MAKE", part("MAKE-001"), null, context);

    assertThat(result.unitPrice()).isEqualByComparingTo("13.77000000");
    assertThat(result.remark()).doesNotContain("取价时点");
    ArgumentCaptor<Wrapper<MakePartPriceCalcRow>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).doesNotContain("price_as_of_time");
    assertThat(paramValues(captor.getValue())).contains("2026-06", "COMMERCIAL");
    assertThat(paramValues(captor.getValue())).doesNotContain(priceAsOfTime);
  }

  @Test
  @DisplayName("MPPG-07：旧 MakeSpecPriceResolver 不再作为 Spring Resolver 注入")
  void oldMakeSpecResolverIsNotSpringService() {
    assertThat(MakeSpecPriceResolver.class.getAnnotation(Service.class)).isNull();
  }

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(code);
    return dto;
  }

  private static void setBusinessUnit(String businessUnitType) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("tester", "N/A", List.of());
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private static String currentMonth() {
    return YearMonth.now().toString();
  }

  private static List<Object> paramValues(Wrapper<MakePartPriceCalcRow> wrapper) {
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }

  private static MakePartPriceCalcRow row(
      String batchId,
      String parentMaterialNo,
      String status,
      Boolean priceComplete,
      String parentTotalCostPrice,
      String costPrice) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setId(1L);
    row.setCalcBatchId(batchId);
    row.setParentMaterialNo(parentMaterialNo);
    row.setStatus(status);
    row.setPriceComplete(priceComplete);
    row.setParentTotalCostPrice(decimal(parentTotalCostPrice));
    row.setCostPrice(decimal(costPrice));
    row.setCreatedAt(LocalDateTime.of(2026, 5, 20, 10, 0));
    return row;
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
