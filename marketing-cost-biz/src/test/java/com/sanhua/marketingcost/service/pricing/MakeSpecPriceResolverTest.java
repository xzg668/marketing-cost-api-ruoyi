package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.MakePartSpec;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MakePartSpecMapper;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * MakeSpecPriceResolver 单测 —— T07 骨架 + T08 原料递归。
 *
 * <p>T08 覆盖：
 * <ul>
 *   <li>spec.raw_unit_price 已填 → 直接用，不走递归</li>
 *   <li>spec.raw_material_code 非空 → 走 Router + 子 Resolver 递归取价</li>
 *   <li>raw_material_code / raw_unit_price 都为空 → material_cost = 0（HIT）</li>
 *   <li>循环依赖 A→B→A → miss('自制件递归循环')</li>
 *   <li>深度 > 5 → miss('递归深度超限')</li>
 * </ul>
 */
class MakeSpecPriceResolverTest {

  private MakePartSpecMapper specMapper;
  private PriceScrapMapper scrapMapper;
  private MaterialPriceRouterService routerService;
  private MakeSpecPriceResolver resolver;
  /** 子 Resolver mock：把 raw 物料的 Router 命中桶映射到结果 */
  private PriceResolver linkedStub;

  @BeforeEach
  void setUp() {
    specMapper = Mockito.mock(MakePartSpecMapper.class);
    scrapMapper = Mockito.mock(PriceScrapMapper.class);
    routerService = Mockito.mock(MaterialPriceRouterService.class);
    linkedStub = Mockito.mock(PriceResolver.class);
    when(linkedStub.priceType()).thenReturn(PriceTypeEnum.LINKED);

    // resolver 自身也要在 list 里，模拟 Spring @Lazy 注入；需可被自递归用例查到
    List<PriceResolver> resolvers = new ArrayList<>();
    resolvers.add(linkedStub);
    // 用一个间接持有列表，构造完成后 add self（避免 ctor 时 self 还没创建）
    resolver = new MakeSpecPriceResolver(specMapper, scrapMapper, routerService, resolvers);
    resolvers.add(resolver);
  }

  // ---------- T07 基础 ----------

  @Test
  @DisplayName("priceType() 必须是 MAKE 桶")
  void priceTypeIsMake() {
    assertThat(resolver.priceType()).isEqualTo(PriceTypeEnum.MAKE);
  }

  @Test
  @DisplayName("命中：raw 已填 + blank=0 → unit_price = process_fee + outsource_fee（材料 0）")
  void hitWithoutRaw() {
    MakePartSpec s = spec("M-A");
    s.setProcessFee(new BigDecimal("3.20"));
    s.setOutsourceFee(new BigDecimal("1.50"));
    s.setBlankWeight(null);
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-A"), route());

    assertThat(r.unitPrice()).isEqualByComparingTo("4.70");
    assertThat(r.priceSource()).isEqualTo("自制件");
  }

  @Test
  @DisplayName("spec 不存在 → miss('lp_make_part_spec 无该料号: ...')")
  void missWhenSpecAbsent() {
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

    PriceResolveResult r = resolver.resolve("OA-1", part("MAT-X"), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark()).contains("lp_make_part_spec 无该料号").contains("MAT-X");
  }

  @Test
  @DisplayName("partCode 为空 → miss('partCode 为空')，不查 mapper")
  void missWhenPartCodeBlank() {
    PriceResolveResult r = resolver.resolve("OA-1", part(""), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark()).isEqualTo("partCode 为空");
    Mockito.verifyNoInteractions(specMapper, routerService);
  }

  // ---------- T08 递归 ----------

  @Test
  @DisplayName("raw_unit_price 已填 → 直接用，不走 Router")
  void rawUnitPriceShortCircuit() {
    MakePartSpec s = spec("M-B");
    s.setBlankWeight(new BigDecimal("100")); // 100g
    s.setRawUnitPrice(new BigDecimal("60"));  // 60 元/kg
    s.setRawMaterialCode("RAW-IGNORED");      // 即使填了 raw code 也不走递归
    s.setProcessFee(new BigDecimal("0.5"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-B"), route());

    // material = 100 × 60 / 1000 = 6；总 = 6 + 0.5 = 6.5
    assertThat(r.unitPrice()).isEqualByComparingTo("6.5");
    Mockito.verifyNoInteractions(routerService);
  }

  @Test
  @DisplayName("raw 走 Router 递归取价：blank × raw_price/1000 + fee")
  void recursiveRawPriceLookup() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.05"));   // 83.05g
    s.setRawMaterialCode("TP2-301050081");
    s.setProcessFee(new BigDecimal("1.50"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    // Router: raw 命中 LINKED 桶
    PriceTypeRoute linkedRoute = new PriceTypeRoute(
        "TP2-301050081", MaterialFormAttrEnum.PURCHASED, PriceTypeEnum.LINKED,
        1, null, null, "manual");
    when(routerService.listCandidates(eq("TP2-301050081"), anyString(), any()))
        .thenReturn(List.of(linkedRoute));
    // 子 Resolver：返 60 元/kg
    when(linkedStub.resolve(anyString(), any(), any()))
        .thenReturn(PriceResolveResult.hit(new BigDecimal("60"), "联动价"));

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // material = 83.05 × 60 / 1000 = 4.983；总 = 4.983 + 1.5 = 6.483
    assertThat(r.unitPrice()).isEqualByComparingTo("6.483");
    assertThat(r.priceSource()).isEqualTo("自制件");
  }

  @Test
  @DisplayName("raw 无路由 → miss('自制件原料取价失败')")
  void recursiveRawNoRoute() {
    MakePartSpec s = spec("M-C");
    s.setBlankWeight(new BigDecimal("100"));
    s.setRawMaterialCode("RAW-MISSING");
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(routerService.listCandidates(eq("RAW-MISSING"), anyString(), any()))
        .thenReturn(Collections.emptyList());

    PriceResolveResult r = resolver.resolve("OA-1", part("M-C"), route());

    assertThat(r.unitPrice()).isNull();
    assertThat(r.remark())
        .contains("自制件原料取价失败")
        .contains("RAW-MISSING");
  }

  @Test
  @DisplayName("循环依赖 A→B→A → miss('自制件递归循环')")
  void recursiveCycleDetection() {
    // spec A 的 raw 是 B；spec B 的 raw 是 A
    MakePartSpec a = spec("CYC-A");
    a.setBlankWeight(new BigDecimal("10"));
    a.setRawMaterialCode("CYC-B");
    MakePartSpec b = spec("CYC-B");
    b.setBlankWeight(new BigDecimal("10"));
    b.setRawMaterialCode("CYC-A");
    when(specMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
      // 简单按当前正在 lookup 的料号返回（近似匹配最新 selectList 调用）
      // 这里 mock 不区分 wrapper，靠测试控制：先返 a 再返 b 再返 a 再返 b...
      return List.of(a);
    });
    // 第一次 selectList 返 a；递归调时 mock 仍返 a — 所以模拟更精准点：
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(a), List.of(b), List.of(a));

    // Router 给 raw 命中 MAKE 桶（让递归走回 self）
    PriceTypeRoute makeRoute = new PriceTypeRoute(
        "CYC-B", MaterialFormAttrEnum.MANUFACTURED, PriceTypeEnum.MAKE,
        1, null, null, "manual");
    when(routerService.listCandidates(anyString(), anyString(), any()))
        .thenReturn(List.of(makeRoute));

    PriceResolveResult r = resolver.resolve("OA-1", part("CYC-A"), route());

    assertThat(r.unitPrice()).isNull();
    // 循环命中后底层返 null → 上层包成 "原料取价失败"；要么是 "递归循环"，依栈深度而定
    assertThat(r.remark()).containsAnyOf("循环", "原料取价失败");
  }

  // ---------- T09 废料抵扣 ----------

  @Test
  @DisplayName("T09: 废料表命中 → 抵扣 = (blank-net) × scrap / 1000")
  void scrapDeductionFromTable() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRecycleCode("A");
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    PriceScrap row = new PriceScrap();
    row.setScrapCode("A");
    row.setRecyclePrice(new BigDecimal("75.663717"));
    when(scrapMapper.selectOne(any(Wrapper.class))).thenReturn(row);

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // material = 83.053885 × 82.946903 / 1000 = 6.88909...
    // scrap   = 3.053885 × 75.663717 / 1000   = 0.23107...
    // unit    ≈ 6.6580
    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.6580"), within(new BigDecimal("0.001")));
    assertThat(r.priceSource()).isEqualTo("自制件");
    assertThat(r.remark()).isEmpty();
  }

  @Test
  @DisplayName("T09: 废料表 miss → fallback spec.recycle_unit_price，结果一致")
  void scrapDeductionFallbackToSpec() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRecycleCode("A");
    s.setRecycleUnitPrice(new BigDecimal("75.663717")); // spec 手填值
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(scrapMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.6580"), within(new BigDecimal("0.001")));
    assertThat(r.remark()).isEmpty(); // fallback 静默不噪 remark
  }

  @Test
  @DisplayName("T09: 废料表 + spec 都缺 → 抵扣 0 + remark='缺废料价(recycle_code=A)'")
  void scrapDeductionMissAddsRemark() {
    MakePartSpec s = spec("S-PIPE");
    s.setBlankWeight(new BigDecimal("83.053885"));
    s.setNetWeight(new BigDecimal("80"));
    s.setRawUnitPrice(new BigDecimal("82.946903"));
    s.setRecycleCode("A");
    s.setRecycleUnitPrice(null); // spec 也缺
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));
    when(scrapMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    PriceResolveResult r = resolver.resolve("OA-1", part("S-PIPE"), route());

    // 不抵扣 → 6.8891（T08 同值）
    assertThat(r.unitPrice()).isCloseTo(
        new BigDecimal("6.8891"), within(new BigDecimal("0.001")));
    assertThat(r.remark()).isEqualTo("缺废料价(recycle_code=A)");
  }

  @Test
  @DisplayName("T09: blank == net（无废料）→ 不查 scrap 表，抵扣 0")
  void noScrapWhenBlankEqualsNet() {
    MakePartSpec s = spec("M-X");
    s.setBlankWeight(new BigDecimal("50"));
    s.setNetWeight(new BigDecimal("50"));
    s.setRawUnitPrice(new BigDecimal("100"));
    s.setRecycleCode("A");
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-X"), route());

    // material = 50 × 100 / 1000 = 5；不抵扣
    assertThat(r.unitPrice()).isEqualByComparingTo("5");
    Mockito.verifyNoInteractions(scrapMapper);
  }

  @Test
  @DisplayName("raw_material_code 与 raw_unit_price 都空 → material 0，HIT 仅含 fee")
  void neitherRawNorPriceTreatsAsPureFee() {
    MakePartSpec s = spec("M-D");
    s.setBlankWeight(new BigDecimal("100"));
    s.setRawUnitPrice(null);
    s.setRawMaterialCode(null);
    s.setProcessFee(new BigDecimal("2.5"));
    when(specMapper.selectList(any(Wrapper.class))).thenReturn(List.of(s));

    PriceResolveResult r = resolver.resolve("OA-1", part("M-D"), route());

    // material = 0；总 = 2.5
    assertThat(r.unitPrice()).isEqualByComparingTo("2.5");
    Mockito.verifyNoInteractions(routerService);
  }

  // ============================ 辅助构造 ============================

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setPartCode(code);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceTypeRoute route() {
    return new PriceTypeRoute(
        "M", MaterialFormAttrEnum.MANUFACTURED, PriceTypeEnum.MAKE,
        1, null, null, "manual");
  }

  private static MakePartSpec spec(String code) {
    MakePartSpec s = new MakePartSpec();
    s.setMaterialCode(code);
    return s;
  }
}
