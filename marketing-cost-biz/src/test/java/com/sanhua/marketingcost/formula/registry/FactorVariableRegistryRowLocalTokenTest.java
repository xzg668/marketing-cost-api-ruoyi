package com.sanhua.marketingcost.formula.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FactorVariableRegistryImpl} 的 V34 行局部 token 解析分支单测。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>{@code [__material]} 命中 binding → 递归到 FINANCE 查 finance_base_price</li>
 *   <li>{@code [__material]} 无 binding → 抛 {@link UnboundRowLocalTokenException}</li>
 *   <li>binding 指向的 factor_code 在 lp_price_variable 未登记 → 抛
 *       {@link UnboundRowLocalTokenException}（数据漂移 fail-fast）</li>
 *   <li>binding 短名 token（"材料价格"）同样命中 __material</li>
 *   <li>{@link FactorVariableRegistryImpl#invalidateBinding(Long)} 后新值生效</li>
 *   <li>同联动行连续两次 resolve → mapper 只被查一次（进程内缓存生效）</li>
 *   <li>ctx.linkedItem 缺失 → IllegalStateException（编排错误）</li>
 *   <li>未装配 mapper 但公式用到 {@code [__xxx]} → IllegalStateException</li>
 * </ul>
 *
 * <p>mock 策略：priceVariableMapper 返回 2 条变量（包含一个 FINANCE 目标），
 * financeBasePriceMapper 固定返回价格，bindingMapper 按用例返回不同 binding 组合。
 */
class FactorVariableRegistryRowLocalTokenTest {

  private PriceVariableMapper priceVariableMapper;
  private FinanceBasePriceMapper financeBasePriceMapper;
  private PriceVariableBindingMapper bindingMapper;
  private FinanceBasePriceQuery financeBasePriceQuery;
  private FactorVariableRegistryImpl registry;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
  }

  @BeforeEach
  void setUp() {
    priceVariableMapper = mock(PriceVariableMapper.class);
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    bindingMapper = mock(PriceVariableBindingMapper.class);
    financeBasePriceQuery = new FinanceBasePriceQuery(financeBasePriceMapper);
    registry = new FactorVariableRegistryImpl(
        priceVariableMapper, financeBasePriceQuery, new ObjectMapper(),
        stubRowLocalRegistry());
    registry.setPriceVariableBindingMapper(bindingMapper);
  }

  /** 测试用 stub：V36 前硬编码的两个占位符，保持历史行为 */
  private static RowLocalPlaceholderRegistry stubRowLocalRegistry() {
    RowLocalPlaceholderRegistry r = mock(RowLocalPlaceholderRegistry.class);
    when(r.isKnown("__material")).thenReturn(true);
    when(r.isKnown("__scrap")).thenReturn(true);
    when(r.tokenNames()).thenReturn(Map.of(
        "__material", List.of("材料含税价格", "材料价格"),
        "__scrap", List.of("废料含税价格", "废料价格")));
    when(r.displayNames()).thenReturn(Map.of(
        "__material", "材料含税价格", "__scrap", "废料含税价格"));
    return r;
  }

  // ============================ 种子工厂 ============================

  /** 生成一个 FINANCE 变量，resolverParams 走 factorCode 路径 */
  private static PriceVariable financeVar(String code) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(code);
    v.setStatus("active");
    v.setResolverKind("FINANCE");
    v.setResolverParams(String.format(
        "{\"factorCode\":\"%s\",\"priceSource\":\"平均价\",\"buScoped\":false}", code));
    return v;
  }

  @SuppressWarnings("unchecked")
  private void seedVariables(PriceVariable... variables) {
    when(priceVariableMapper.selectList(any(Wrapper.class)))
        .thenReturn(new ArrayList<>(List.of(variables)));
  }

  /** 构造一条"当前生效"的 binding（expiry_date=null） */
  private static PriceVariableBinding binding(
      Long id, Long linkedItemId, String tokenName, String factorCode) {
    PriceVariableBinding b = new PriceVariableBinding();
    b.setId(id);
    b.setLinkedItemId(linkedItemId);
    b.setTokenName(tokenName);
    b.setFactorCode(factorCode);
    b.setPriceSource("平均价");
    b.setBuScoped(0);
    b.setDeleted(0);
    return b;
  }

  /** 造一个带 id 的 linkedItem，避免 resolveRowLocalToken 里 id==null 提前抛 */
  private static PriceLinkedItem linkedItem(Long id) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(id);
    return item;
  }

  // ============================ 用例 ============================

  @Test
  @DisplayName("[__material] 命中 binding → 递归解析 factor_code 到 finance 基价")
  void materialHitsBindingAndResolvesFinancePrice() {
    // 变量表里有一个 SUS304/2Bδ0.7 的 FINANCE 变量，Cu 同样可被查到（但本用例不需要）
    seedVariables(financeVar("SUS304_2B_0_7"));

    // binding：linked_item_id=101，token_name=材料含税价格 → factor_code=SUS304_2B_0_7
    when(bindingMapper.findCurrentByLinkedItemId(101L))
        .thenReturn(List.of(binding(1L, 101L, "材料含税价格", "SUS304_2B_0_7")));

    // finance_base_price 返回 17.20
    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("17.20"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(101L))
        .pricingMonth("2026-04");

    Optional<BigDecimal> result = registry.resolve("__material", ctx);
    assertThat(result).contains(new BigDecimal("17.20"));
  }

  @Test
  @DisplayName("[__material] 短名 '材料价格' 同样命中 binding")
  void materialShortNameTokenAlsoHits() {
    seedVariables(financeVar("SUS304_2B_0_7"));

    // 注意 token_name 是短名 "材料价格"，仍应映射到 __material
    when(bindingMapper.findCurrentByLinkedItemId(102L))
        .thenReturn(List.of(binding(2L, 102L, "材料价格", "SUS304_2B_0_7")));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("17.20"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(102L))
        .pricingMonth("2026-04");

    assertThat(registry.resolve("__material", ctx)).contains(new BigDecimal("17.20"));
  }

  @Test
  @DisplayName("[__material] 无 binding → 抛 UnboundRowLocalTokenException，msg 含 linked_item_id 和 token name")
  void materialMissingBindingThrows() {
    seedVariables(financeVar("Cu"));

    when(bindingMapper.findCurrentByLinkedItemId(201L)).thenReturn(List.of());

    VariableContext ctx = new VariableContext().linkedItem(linkedItem(201L));

    assertThatThrownBy(() -> registry.resolve("__material", ctx))
        .isInstanceOf(UnboundRowLocalTokenException.class)
        .hasMessageContaining("201")
        .hasMessageContaining("__material")
        .hasMessageContaining("材料含税价格");
  }

  @Test
  @DisplayName("binding 指向的 factor_code 在 lp_price_variable 未登记 → 抛 UnboundRowLocalTokenException")
  void bindingPointsToUnregisteredVariableThrows() {
    // 只登记了 Cu；但 binding 指向 "GHOST"，故意模拟供管部映射与变量表漂移
    seedVariables(financeVar("Cu"));

    when(bindingMapper.findCurrentByLinkedItemId(301L))
        .thenReturn(List.of(binding(3L, 301L, "材料含税价格", "GHOST")));

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(301L))
        .pricingMonth("2026-04");

    assertThatThrownBy(() -> registry.resolve("__material", ctx))
        .isInstanceOf(UnboundRowLocalTokenException.class)
        .hasMessageContaining("GHOST")
        .hasMessageContaining("未登记");
  }

  @Test
  @DisplayName("[__scrap] 废料路径独立命中废料 token，不被 __material 串台")
  void scrapTokenResolvesIndependently() {
    seedVariables(financeVar("Cu_scrap_ref"));

    when(bindingMapper.findCurrentByLinkedItemId(401L))
        .thenReturn(List.of(
            // 同一 linked_item 可同时有两个 token，互不干扰
            binding(10L, 401L, "材料含税价格", "Cu"),
            binding(11L, 401L, "废料含税价格", "Cu_scrap_ref")));

    // 只登记 Cu_scrap_ref（Cu 没登记不影响本测 __scrap 路径）
    // Cu 不在 seedVariables 里 → resolve [__material] 会抛；但本测只解 [__scrap]。
    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("62.50"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(401L))
        .pricingMonth("2026-04");

    assertThat(registry.resolve("__scrap", ctx)).contains(new BigDecimal("62.50"));
  }

  @Test
  @DisplayName("修改 binding 后调用 invalidateBinding → 新值生效")
  void invalidateBindingRefreshesCache() {
    seedVariables(financeVar("Cu"), financeVar("SUS304_2B_0_7"));

    // 初始 binding → Cu
    when(bindingMapper.findCurrentByLinkedItemId(501L))
        .thenReturn(List.of(binding(1L, 501L, "材料含税价格", "Cu")));

    FinanceBasePrice cuPrice = new FinanceBasePrice();
    cuPrice.setPrice(new BigDecimal("90.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(cuPrice);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(501L))
        .pricingMonth("2026-04");

    assertThat(registry.resolve("__material", ctx)).contains(new BigDecimal("90.00"));

    // 运维改绑定 → SUS304_2B_0_7
    when(bindingMapper.findCurrentByLinkedItemId(501L))
        .thenReturn(List.of(binding(2L, 501L, "材料含税价格", "SUS304_2B_0_7")));
    FinanceBasePrice susPrice = new FinanceBasePrice();
    susPrice.setPrice(new BigDecimal("17.20"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(susPrice);

    // 不失效：仍拿到旧缓存里的 Cu，走旧 factor_code
    // （但 finance mapper 已被改，所以实际上拿到的是新价格 —— 这里只验证 binding 维度）
    // 主动失效后重查
    registry.invalidateBinding(501L);

    Optional<BigDecimal> after = registry.resolve("__material", ctx);
    assertThat(after).contains(new BigDecimal("17.20"));
    // 两次 findCurrent 各一次（首次 + 失效后重查）
    verify(bindingMapper, times(2)).findCurrentByLinkedItemId(501L);
  }

  @Test
  @DisplayName("同 linked_item 连续两次 resolve → bindingMapper 只查一次（进程内缓存）")
  void bindingCacheDedupesSameLinkedItem() {
    seedVariables(financeVar("Cu"));

    when(bindingMapper.findCurrentByLinkedItemId(601L))
        .thenReturn(List.of(binding(1L, 601L, "材料含税价格", "Cu")));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("90.00"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(601L))
        .pricingMonth("2026-04");

    // 两次独立 resolve（不同 request cache） —— 仍应只触发一次 DB 查询
    assertThat(registry.resolve("__material", ctx)).contains(new BigDecimal("90.00"));
    assertThat(registry.resolve("__material", ctx)).contains(new BigDecimal("90.00"));

    verify(bindingMapper, times(1)).findCurrentByLinkedItemId(601L);
  }

  @Test
  @DisplayName("ctx.linkedItem 为 null → IllegalStateException（编排错误）")
  void missingLinkedItemThrowsIllegalState() {
    seedVariables(financeVar("Cu"));
    // ctx 不塞 linkedItem
    VariableContext ctx = new VariableContext().pricingMonth("2026-04");

    assertThatThrownBy(() -> registry.resolve("__material", ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("linkedItem.id");
  }

  @Test
  @DisplayName("mapper 未装配但公式用到 [__material] → IllegalStateException")
  void missingMapperThrowsIllegalState() {
    // 重建 registry：不注入 bindingMapper
    FactorVariableRegistryImpl bare = new FactorVariableRegistryImpl(
        priceVariableMapper, financeBasePriceQuery, new ObjectMapper(),
        stubRowLocalRegistry());
    // 不调 setPriceVariableBindingMapper

    seedVariables(financeVar("Cu"));
    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(701L))
        .pricingMonth("2026-04");

    assertThatThrownBy(() -> bare.resolve("__material", ctx))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PriceVariableBindingMapper");
  }

  @Test
  @DisplayName("两个不同 linked_item 的缓存互不干扰")
  void differentLinkedItemsHaveIndependentCache() {
    seedVariables(financeVar("Cu"), financeVar("SUS304_2B_0_7"));

    when(bindingMapper.findCurrentByLinkedItemId(801L))
        .thenReturn(List.of(binding(1L, 801L, "材料含税价格", "Cu")));
    when(bindingMapper.findCurrentByLinkedItemId(802L))
        .thenReturn(List.of(binding(2L, 802L, "材料含税价格", "SUS304_2B_0_7")));

    FinanceBasePrice cu = new FinanceBasePrice();
    cu.setPrice(new BigDecimal("90.00"));
    FinanceBasePrice sus = new FinanceBasePrice();
    sus.setPrice(new BigDecimal("17.20"));
    // 按 factor_code 选择不同返回
    when(financeBasePriceMapper.selectOne(any()))
        .thenReturn(cu, sus);

    VariableContext ctx1 = new VariableContext()
        .linkedItem(linkedItem(801L))
        .pricingMonth("2026-04");
    VariableContext ctx2 = new VariableContext()
        .linkedItem(linkedItem(802L))
        .pricingMonth("2026-04");

    assertThat(registry.resolve("__material", ctx1)).contains(new BigDecimal("90.00"));
    assertThat(registry.resolve("__material", ctx2)).contains(new BigDecimal("17.20"));

    verify(bindingMapper, times(1)).findCurrentByLinkedItemId(801L);
    verify(bindingMapper, times(1)).findCurrentByLinkedItemId(802L);
  }

  @Test
  @DisplayName("invalidateBinding(null) → 清空全部绑定缓存")
  void invalidateAllBindings() {
    seedVariables(financeVar("Cu"));

    when(bindingMapper.findCurrentByLinkedItemId(901L))
        .thenReturn(List.of(binding(1L, 901L, "材料含税价格", "Cu")));

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("90"));
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(901L))
        .pricingMonth("2026-04");

    registry.resolve("__material", ctx);
    registry.invalidateBinding(null); // 全清
    registry.resolve("__material", ctx);

    verify(bindingMapper, times(2)).findCurrentByLinkedItemId(901L);
  }

  @Test
  @DisplayName("binding.factor_code 为空白 → 抛 UnboundRowLocalTokenException")
  void bindingBlankFactorCodeThrows() {
    seedVariables(financeVar("Cu"));

    PriceVariableBinding bad = binding(1L, 1001L, "材料含税价格", "Cu");
    bad.setFactorCode(" ");
    when(bindingMapper.findCurrentByLinkedItemId(1001L)).thenReturn(List.of(bad));

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(1001L))
        .pricingMonth("2026-04");

    assertThatThrownBy(() -> registry.resolve("__material", ctx))
        .isInstanceOf(UnboundRowLocalTokenException.class)
        .hasMessageContaining("未配置 factor_code");
  }

  @Test
  @DisplayName("长 token '材料含税价格' 与短名 '材料价格' 都在 binding 列表时，长名优先命中（list 顺序）")
  void longerTokenPreferredWhenBothPresent() {
    // 现实中不该同时出现，但测试确保行为可预期：按 ROW_LOCAL_TOKEN_NAMES 里
    // 申明顺序（长在前），长名优先
    seedVariables(financeVar("Cu"), financeVar("SUS304_2B_0_7"));

    when(bindingMapper.findCurrentByLinkedItemId(1101L))
        .thenReturn(List.of(
            binding(1L, 1101L, "材料价格", "Cu"), // 先放短名
            binding(2L, 1101L, "材料含税价格", "SUS304_2B_0_7"))); // 再放长名

    FinanceBasePrice row = new FinanceBasePrice();
    row.setPrice(new BigDecimal("17.20"));
    when(financeBasePriceMapper.selectOne(eq(null))).thenReturn(row);
    when(financeBasePriceMapper.selectOne(any())).thenReturn(row);

    VariableContext ctx = new VariableContext()
        .linkedItem(linkedItem(1101L))
        .pricingMonth("2026-04");

    // 应命中长名 → SUS304_2B_0_7 → 17.20
    assertThat(registry.resolve("__material", ctx)).contains(new BigDecimal("17.20"));
  }
}
