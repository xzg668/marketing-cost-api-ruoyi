package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FixedPriceResolverTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceFixedItem.class);
  }

  @Test
  @DisplayName("T23：固定采购价按月度 price_as_of_time 命中有效版本")
  void monthlyPurchaseFixedUsesContextPriceAsOfTime() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    FixedPriceResolver resolver = resolver(mapper);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(fixed("12.340000", "PURCHASE_FIXED")));
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 10, 10, 30);

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-FIXED"),
            route("固定采购价"),
            monthlyContext(priceAsOfTime));

    assertThat(result.unitPrice()).isEqualByComparingTo("12.340000");
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "source_type", "effective_from", "effective_to")
        .contains("ORDER BY", "effective_from", "DESC");
    assertThat(paramValues(captor.getValue())).contains(LocalDate.of(2026, 5, 10));
  }

  @Test
  @DisplayName("T23：固定采购价结束日当天仍然有效")
  void fixedPriceEffectiveToIsInclusive() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    FixedPriceResolver resolver = resolver(mapper);
    PriceFixedItem row = fixed("7.256637", "PURCHASE_FIXED");
    row.setEffectiveTo(LocalDate.of(2026, 6, 30));
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("301990444"),
            route("固定价"),
            monthlyContext(LocalDateTime.of(2026, 6, 30, 23, 59, 59)));

    assertThat(result.unitPrice()).isEqualByComparingTo("7.256637");
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("effective_to", ">=");
    assertThat(paramValues(captor.getValue())).contains(LocalDate.of(2026, 6, 30));
  }

  @Test
  @DisplayName("T23：结算价使用 SETTLE 来源并按 price_as_of_time 过滤，不能直接取最新")
  void monthlySettleFixedUsesSettleSourceAndContextPriceAsOfTime() {
    PriceFixedItemMapper mapper = mock(PriceFixedItemMapper.class);
    FixedPriceResolver resolver = resolver(mapper);
    PriceFixedItem row = fixed("8.880000", "SETTLE_FIXED");
    row.setPricingMonth("2026-05");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 20, 14, 0);

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-SETTLE"),
            route("结算价"),
            monthlyContext(priceAsOfTime));

    assertThat(result.unitPrice()).isEqualByComparingTo("8.880000");
    assertThat(result.priceSource()).isEqualTo("结算固定价");
    assertThat(result.remark()).contains("结算期间=2026-05");
    ArgumentCaptor<Wrapper<PriceFixedItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("source_type", "effective_from", "effective_to", "pricing_month");
    assertThat(paramValues(captor.getValue()))
        .contains("SETTLE_FIXED", "SETTLE", LocalDate.of(2026, 5, 20));
  }

  private static FixedPriceResolver resolver(PriceFixedItemMapper mapper) {
    return new FixedPriceResolver(
        mapper,
        new SupplierPreferredPriceSelector(mock(SupplierSupplyRatioResolveService.class)));
  }

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(code);
    return item;
  }

  private static PriceTypeRoute route(String rawPriceType) {
    return new PriceTypeRoute(
        "MAT",
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.FIXED,
        1,
        LocalDate.of(2026, 5, 1),
        null,
        "manual",
        rawPriceType);
  }

  private static CostRunContext monthlyContext(LocalDateTime priceAsOfTime) {
    return CostRunContext.monthlyReprice(
        "2026-05",
        88L,
        "MRP-001",
        "COMMERCIAL",
        priceAsOfTime,
        CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        "OA-001",
        1L,
        "P-001",
        null,
        null,
        "OBJ-001");
  }

  private static PriceFixedItem fixed(String price, String sourceType) {
    PriceFixedItem item = new PriceFixedItem();
    item.setMaterialCode("MAT");
    item.setSourceType(sourceType);
    item.setSupplierCode("S-001");
    item.setFixedPrice(new BigDecimal(price));
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return item;
  }

  private static List<Object> paramValues(Wrapper<PriceFixedItem> wrapper) {
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }
}
