package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl;
import com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

class FinanceFactorVariableQueryTest {
  private final FinanceFactorPriceReader reader = mock(FinanceFactorPriceReader.class);
  private final FactorIdentityMapper identities = mock(FactorIdentityMapper.class);
  private final FactorMonthlyPriceMapper prices = mock(FactorMonthlyPriceMapper.class);
  private final FinanceFactorVariableQuery query =
      new FinanceFactorVariableQuery(
          reader,
          identities,
          prices,
          new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
  private final Map<String, FactorPriceSnapshot.FactorValue> values = new LinkedHashMap<>();
  private VariableContext context;

  @BeforeEach
  void setup() {
    var rule = new PriceLinkedItem();
    rule.setMaterialCode("MAT-1");
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setOrgCode("210");
    context = new VariableContext().linkedItem(rule).pricingMonth("2026-11");
    values.put(PriceFactorContext.COPPER, factor("90123.4567890123456"));
    values.put("AVG|1#ZN", factor("21636"));
    when(reader.readEffective(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              FinanceFactorImportContext request = invocation.getArgument(0);
              FactorPriceScope.Condition condition = invocation.getArgument(1);
              return new PriceFactorContext(
                  "MAT-1",
                  new PriceMonthSelection(request.priceMonth(), "2026-10"),
                  new FactorPriceSnapshot(
                      new FactorPriceScope(
                          request.businessUnitType(),
                          request.priceOrgCode(),
                          "2026-10",
                          "A",
                          condition),
                      10L,
                      2,
                      "财务.xlsx",
                      "sha",
                      "content",
                      values,
                      List.of("A"),
                      "财务",
                      LocalDateTime.of(2026, 10, 1, 0, 0),
                      "审核"),
                  null);
            });
  }

  @Test
  void canonicalCopperAliasesUsePublishedScopeAndExactUnitConversion() {
    for (String alias : List.of("Cu", "cu", "1#Cu")) {
      var value =
          query.query(Map.of("factorCode", alias, "priceSource", "月均价"), context).orElseThrow();
      assertThat(value.price()).isEqualByComparingTo("90.1234567890123456");
      assertThat(value.monthlyPriceId()).isEqualTo(101L);
      assertThat(value.sourceMonth()).isEqualTo("2026-10");
    }
    verify(reader, times(3))
        .readEffective(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, null),
            FactorPriceScope.Condition.GENERAL,
            "MAT-1");
  }

  @Test
  void customerCopperOverrideNeverChangesZincAndMonthlyRepriceUsesFinance() {
    var form = new OaForm();
    form.setCopperPrice(new BigDecimal("100000"));
    form.setZincPrice(new BigDecimal("99000"));
    context.oaForm(form);
    var copper = Map.<String, Object>of("factorCode", "Cu", "priceSource", "平均价");
    var zinc = Map.<String, Object>of("shortName", "1#Zn", "priceSource", "平均价");
    assertThat(query.query(copper, context).orElseThrow().price()).isEqualByComparingTo("100");
    assertThat(query.query(zinc, context).orElseThrow().price()).isEqualByComparingTo("21.636");
    context.override("Cu", new BigDecimal("95"), "FINANCE_QUOTE_BASE");
    assertThat(query.query(copper, context).orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.price()).isEqualByComparingTo("95");
              assertThat(value.source()).isEqualTo("FINANCE_QUOTE_BASE");
            });
    context.priceContextType(VariableContext.PriceContextType.MONTHLY_REPRICE);
    assertThat(query.query(copper, context).orElseThrow().price())
        .isEqualByComparingTo("90.1234567890123456");
  }

  @Test
  void factorIdentityMustMatchBusinessAndSharesSamePublishedKey() {
    var identity = new FactorIdentity();
    identity.setId(99L);
    identity.setBusinessUnitType("COMMERCIAL");
    identity.setStatus("ACTIVE");
    identity.setCanonicalFactorKey("AVG|CU");
    when(identities.selectById(99L)).thenReturn(identity);
    assertThat(query.query(Map.of("factorIdentityId", 99L), context).orElseThrow().price())
        .isEqualByComparingTo("90.1234567890123456");
    identity.setBusinessUnitType("HOUSEHOLD");
    assertThatThrownBy(() -> query.query(Map.of("factorIdentityId", 99L), context))
        .hasMessageContaining("业务单元");
  }

  @Test
  void missingPublicFactorNeverFallsBackToTechnicalMonthPrice() {
    values.remove("AVG|1#ZN");
    when(prices.countPublishedFactorKey("COMMERCIAL", "210", "AVG|1#ZN")).thenReturn(1L);
    assertThatThrownBy(
            () -> query.query(Map.of("shortName", "1#Zn", "priceSource", "平均价"), context))
        .hasMessageContaining("财务方案缺少因素");
    values.remove(PriceFactorContext.COPPER);
    assertThatThrownBy(() -> query.query(Map.of("factorCode", "Cu", "priceSource", "平均价"), context))
        .hasMessageContaining("财务方案缺少因素");
    verify(prices, never()).selectById(any(java.io.Serializable.class));
  }

  @Test
  void privateNonPublicFactorMayContinueToItsOwnMonthlySource() {
    assertThat(query.query(Map.of("shortName", "工装维护费", "priceSource", "采购价"), context)).isEmpty();
    verify(prices).countPublishedFactorKey("COMMERCIAL", "210", "PURCHASE|工装维护费");
  }

  @Test
  void missingScopeOrIdentityMetadataFailsExplicitly() {
    assertThatThrownBy(() -> query.query(Map.of("factorCode", "Cu"), context))
        .hasMessageContaining("价源");
    assertThatThrownBy(
            () ->
                query.query(
                    Map.of("factorCode", "Cu", "priceSource", "平均价"), new VariableContext()))
        .hasMessageContaining("组织上下文");
    context.pricingMonth(null);
    assertThatThrownBy(() -> query.query(Map.of("factorCode", "Cu", "priceSource", "平均价"), context))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void derivedNameCannotChooseBetweenDifferentPriceSources() {
    values.put("PURCHASE|1#ZN", factor("22000"));
    assertThatThrownBy(() -> query.queryName("1#Zn", context)).hasMessageContaining("多个价源");
    assertThat(query.queryName("Cu", context)).contains(new BigDecimal("90.1234567890123456"));
  }

  private FactorPriceSnapshot.FactorValue factor(String price) {
    return new FactorPriceSnapshot.FactorValue(
        1L,
        101L,
        "因素",
        "简称",
        new BigDecimal(price),
        "CNY/TON",
        true,
        List.of(new FactorPriceSnapshot.Source(2L, "基价", "D3", price, "元/吨", null, null, "单位换算")));
  }
}
