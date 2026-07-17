package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LinkedPriceResolverTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
  }

  @Test
  @DisplayName("QUOTE 联动价按 oaNo + partCode + pricingMonth 读取最新 OK calc_item 单价")
  void resolvesLatestOkCalcItemByOaNoPartCodeAndPricingMonth() {
    PriceLinkedCalcItemMapper mapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    LinkedPriceResolver resolver = new LinkedPriceResolver(mapper);

    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
    calc.setId(9001L);
    calc.setOaNo("OA-V3");
    calc.setItemCode("MAT-LINKED");
    calc.setPricingMonth("2026-06");
    calc.setCalcStatus("OK");
    calc.setPartUnitPrice(new BigDecimal("72.000000"));
    calc.setTraceJson("{\"variables\":{\"factor_identity_191\":72.000000},\"result\":72.000000}");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(calc));

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode("MAT-LINKED");
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 6, 12, 15, 30);

    PriceResolveResult result =
        resolver.resolve(
            "OA-V3",
            item,
            null,
            CostRunContext.quote(
                "OA-V3",
                9L,
                "P-001",
                "箱装",
                "客户A",
                "COMMERCIAL",
                "2026-06",
                priceAsOfTime,
                "QUOTE:9"));

    assertThat(result.unitPrice()).isEqualByComparingTo("72.000000");
    assertThat(result.priceSource()).isEqualTo("联动价");
    assertThat(result.remark()).isEmpty();
    assertThat(result.resultRefId()).isEqualTo(9001L);
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("oa_no", "item_code", "calc_scene", "pricing_month", "price_as_of_time", "calc_status")
        .contains("part_unit_price IS NOT NULL")
        .contains("ORDER BY", "id", "DESC")
        .contains("LIMIT 1");
  }

  @Test
  @DisplayName("财务报价只读取 FINANCE_QUOTE_BASE 联动价结果")
  void financeQuoteReadsFinanceFactorSource() {
    PriceLinkedCalcItemMapper mapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    LinkedPriceResolver resolver = new LinkedPriceResolver(mapper);
    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
    calc.setId(9010L);
    calc.setPartUnitPrice(new BigDecimal("90"));
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(calc));
    CostRunContext context = CostRunContext.quote(
        "OA-1", 1L, "TOP", null, null, "COMMERCIAL", "2026-05", "KEY");
    context.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode("MAT-CU");

    PriceResolveResult result = resolver.resolve("OA-1", item, null, context);

    assertThat(result.resultRefId()).isEqualTo(9010L);
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains("factor_source");
    assertThat(((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
            captor.getValue()).getParamNameValuePairs().values())
        .contains("FINANCE_QUOTE_BASE");
  }

  @Test
  @DisplayName("V3-10：本 OA 没有刷新结果时联动价明确 miss，不带空价继续算")
  void missingCalcItemReturnsMiss() {
    PriceLinkedCalcItemMapper mapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    LinkedPriceResolver resolver = new LinkedPriceResolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode("MAT-MISSING");

    PriceResolveResult result = resolver.resolve("OA-V3", item, null);

    assertThat(result.unitPrice()).isNull();
    assertThat(result.remark())
        .contains("lp_price_linked_calc_item 无可用 OK 记录")
        .contains("OA-V3")
        .contains("MAT-MISSING");
  }

  @Test
  @DisplayName("T7：月度调价联动价按 MONTHLY_ADJUST + 调价批次读取，不读 OA QUOTE 结果")
  void monthlyRepriceResolvesByAdjustBatchContext() {
    PriceLinkedCalcItemMapper mapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    LinkedPriceResolver resolver = new LinkedPriceResolver(mapper);

    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
    calc.setItemCode("MAT-LINKED");
    calc.setPartUnitPrice(new BigDecimal("88.000000"));
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(calc));

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode("MAT-LINKED");

    PriceResolveResult result =
        resolver.resolve(
            "OA-V3",
            item,
            null,
            CostRunContext.monthlyReprice(
                "2026-05",
                77L,
                "MRP-001",
                "COMMERCIAL",
                "OA-V3",
                9L,
                "P-001",
                "箱装",
                "客户A",
                "OBJ-001"));

    assertThat(result.unitPrice()).isEqualByComparingTo("88.000000");
    assertThat(result.priceSource()).isEqualTo("月度调价联动价");
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("calc_scene", "adjust_batch_id", "business_unit_type", "pricing_month", "item_code")
        .doesNotContain("oa_no");
  }
}
