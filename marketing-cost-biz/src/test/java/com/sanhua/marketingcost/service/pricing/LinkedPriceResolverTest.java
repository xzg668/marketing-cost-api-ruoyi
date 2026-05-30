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
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import java.math.BigDecimal;
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
  @DisplayName("V3-10：联动价取价按 oaNo + partCode 读取最新 calc_item 单价")
  void resolvesLatestCalcItemByOaNoAndPartCode() {
    PriceLinkedCalcItemMapper mapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    LinkedPriceResolver resolver = new LinkedPriceResolver(mapper);

    PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
    calc.setId(9001L);
    calc.setOaNo("OA-V3");
    calc.setItemCode("MAT-LINKED");
    calc.setPartUnitPrice(new BigDecimal("72.000000"));
    calc.setTraceJson("{\"variables\":{\"factor_identity_191\":72.000000},\"result\":72.000000}");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(calc));

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode("MAT-LINKED");

    PriceResolveResult result = resolver.resolve("OA-V3", item, null);

    assertThat(result.unitPrice()).isEqualByComparingTo("72.000000");
    assertThat(result.priceSource()).isEqualTo("联动价");
    assertThat(result.remark()).isEmpty();
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("oa_no", "item_code", "calc_scene")
        .contains("ORDER BY", "id", "DESC")
        .contains("LIMIT 1");
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
        .contains("lp_price_linked_calc_item 无记录")
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
