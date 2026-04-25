package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link FinanceBasePriceQuery} 单测 —— 覆盖四键组装与严格校验的 8 个场景。
 *
 * <p>重点验证：
 * <ol>
 *   <li>factorCode/shortName 二选一 + factorCode 优先</li>
 *   <li>buScoped=true 时 bu 缺失直接 empty，不查库</li>
 *   <li>priceSource / pricingMonth 缺失同样直接 empty</li>
 *   <li>命中后透传 FinanceBasePrice</li>
 * </ol>
 */
class FinanceBasePriceQueryTest {

  private FinanceBasePriceMapper mapper;
  private FinanceBasePriceQuery query;

  @BeforeAll
  static void initTableInfo() {
    // MP Lambda wrapper 依赖 TableInfo 缓存；非 Spring 启动场景需手工预热。
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
  }

  @BeforeEach
  void setUp() {
    mapper = mock(FinanceBasePriceMapper.class);
    query = new FinanceBasePriceQuery(mapper);
  }

  @Test
  @DisplayName("factorCode 非空 → 按 factor_code 过滤")
  void factorCodeWins() {
    FinanceBasePrice row = priceRow(new BigDecimal("90.12"));
    when(mapper.selectOne(any())).thenReturn(row);

    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        "Cu", "电解铜", "平均价", true, "2024-03", "COMMERCIAL", "Cu");

    assertThat(hit).isPresent();
    assertThat(hit.get().getPrice()).isEqualTo(new BigDecimal("90.12"));

    // 捕获 wrapper，校验 SQL 条件里没有 short_name（factorCode 路径下不能叠加）
    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor = wrapperCaptor();
    verify(mapper).selectOne(captor.capture());
    String sql = ((AbstractWrapper<?, ?, ?>) captor.getValue()).getSqlSegment();
    assertThat(sql).contains("factor_code").doesNotContain("short_name");
  }

  @Test
  @DisplayName("factorCode 空 → 回退 short_name")
  void shortNameFallback() {
    when(mapper.selectOne(any())).thenReturn(priceRow(new BigDecimal("55")));

    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        null, "美国柜装黄铜", "平均价", true, "2024-03", "COMMERCIAL", "us_brass_price");

    assertThat(hit).isPresent();
    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor = wrapperCaptor();
    verify(mapper).selectOne(captor.capture());
    String sql = ((AbstractWrapper<?, ?, ?>) captor.getValue()).getSqlSegment();
    assertThat(sql).contains("short_name").doesNotContain("factor_code");
  }

  @Test
  @DisplayName("buScoped=true → 加 business_unit_type 过滤；buScoped=false 不加")
  void buScopedAddsBuFilter() {
    when(mapper.selectOne(any())).thenReturn(priceRow(new BigDecimal("1")));

    // buScoped=true
    query.queryLatestBasePrice("Cu", null, "平均价", true, "2024-03", "COMMERCIAL", "Cu");
    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor1 = wrapperCaptor();
    verify(mapper).selectOne(captor1.capture());
    assertThat(((AbstractWrapper<?, ?, ?>) captor1.getValue()).getSqlSegment())
        .contains("business_unit_type");

    // buScoped=false（用新 mapper 避免 verify 次数干扰）
    FinanceBasePriceMapper mapper2 = mock(FinanceBasePriceMapper.class);
    when(mapper2.selectOne(any())).thenReturn(priceRow(new BigDecimal("1")));
    FinanceBasePriceQuery q2 = new FinanceBasePriceQuery(mapper2);
    q2.queryLatestBasePrice("Cu", null, "平均价", false, "2024-03", null, "Cu");
    ArgumentCaptor<Wrapper<FinanceBasePrice>> captor2 = wrapperCaptor();
    verify(mapper2).selectOne(captor2.capture());
    assertThat(((AbstractWrapper<?, ?, ?>) captor2.getValue()).getSqlSegment())
        .doesNotContain("business_unit_type");
  }

  @Test
  @DisplayName("buScoped=true 但 bu 空 → empty，不查库")
  void buScopedButBuMissing() {
    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        "Cu", null, "平均价", true, "2024-03", null, "Cu");

    assertThat(hit).isEmpty();
    verify(mapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("priceSource 缺失 → empty，不查库")
  void priceSourceRequired() {
    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        "Cu", null, null, true, "2024-03", "COMMERCIAL", "Cu");

    assertThat(hit).isEmpty();
    verify(mapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("pricingMonth 缺失 → empty，不查库（严格模式不回退）")
  void pricingMonthRequired() {
    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        "Cu", null, "平均价", true, null, "COMMERCIAL", "Cu");

    assertThat(hit).isEmpty();
    verify(mapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("factorCode / shortName 都缺 → empty，不查库")
  void bothNamesMissing() {
    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        null, "", "平均价", true, "2024-03", "COMMERCIAL", "unknown");

    assertThat(hit).isEmpty();
    verify(mapper, never()).selectOne(any());
  }

  @Test
  @DisplayName("mapper 返回 null → empty")
  void mapperNoHit() {
    when(mapper.selectOne(any())).thenReturn(null);

    Optional<FinanceBasePrice> hit = query.queryLatestBasePrice(
        "Cu", null, "平均价", true, "2024-03", "COMMERCIAL", "Cu");

    assertThat(hit).isEmpty();
  }

  private static FinanceBasePrice priceRow(BigDecimal price) {
    FinanceBasePrice p = new FinanceBasePrice();
    p.setPrice(price);
    return p;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static ArgumentCaptor<Wrapper<FinanceBasePrice>> wrapperCaptor() {
    return ArgumentCaptor.forClass((Class) Wrapper.class);
  }
}
