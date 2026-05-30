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
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RangePriceResolverTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PriceRangeItem.class);
  }

  @Test
  @DisplayName("T23：区间价按 price_as_of_time 和数量命中有效区间")
  void monthlyRangeUsesContextPriceAsOfTimeAndQuantity() {
    PriceRangeItemMapper mapper = mock(PriceRangeItemMapper.class);
    RangePriceResolver resolver = new RangePriceResolver(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(range("0", "9", "5.000000"), range("10", "99", "7.500000")));
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 15, 9, 0);

    PriceResolveResult result =
        resolver.resolve(
            "OA-001",
            part("MAT-RANGE", "12"),
            route(),
            monthlyContext(priceAsOfTime));

    assertThat(result.unitPrice()).isEqualByComparingTo("7.500000");
    assertThat(result.priceSource()).isEqualTo("区间价");
    assertThat(result.remark()).contains("区间命中(10-99,qty=12");
    ArgumentCaptor<Wrapper<PriceRangeItem>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("material_code", "effective_from", "effective_to")
        .contains("ORDER BY", "effective_from", "DESC");
    assertThat(paramValues(captor.getValue())).contains(LocalDate.of(2026, 5, 15));
  }

  private static CostRunPartItemDto part(String code, String qty) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setPartCode(code);
    item.setPartQty(new BigDecimal(qty));
    return item;
  }

  private static PriceTypeRoute route() {
    return new PriceTypeRoute(
        "MAT-RANGE",
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.RANGE,
        1,
        LocalDate.of(2026, 5, 1),
        null,
        "manual",
        "区间价");
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

  private static PriceRangeItem range(String low, String high, String price) {
    PriceRangeItem item = new PriceRangeItem();
    item.setMaterialCode("MAT-RANGE");
    item.setRangeLow(new BigDecimal(low));
    item.setRangeHigh(new BigDecimal(high));
    item.setPriceInclTax(new BigDecimal(price));
    item.setEffectiveFrom(LocalDate.of(2026, 5, 1));
    return item;
  }

  private static List<Object> paramValues(Wrapper<PriceRangeItem> wrapper) {
    AbstractWrapper<?, ?, ?> abstractWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
    return List.copyOf(abstractWrapper.getParamNameValuePairs().values());
  }
}
