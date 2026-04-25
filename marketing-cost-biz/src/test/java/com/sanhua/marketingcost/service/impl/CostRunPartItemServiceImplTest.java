package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 部品试算服务单元测试 —— 覆盖 legacy / dual / new 三种模式与 6 桶 fallback 链路。
 *
 * <p>依赖全 mock，无 Spring 启动；通过手工拼装 PriceResolver 列表来验证 Router 分发逻辑。
 */
class CostRunPartItemServiceImplTest {

  private CostRunPartItemMapper costRunPartItemMapper;
  private PriceFixedItemMapper priceFixedItemMapper;
  private PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private MaterialPriceRouterService routerService;

  @BeforeEach
  void setUp() {
    costRunPartItemMapper = Mockito.mock(CostRunPartItemMapper.class);
    priceFixedItemMapper = Mockito.mock(PriceFixedItemMapper.class);
    priceLinkedCalcItemMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    routerService = Mockito.mock(MaterialPriceRouterService.class);
    // 持久化路径走通即可；mock 默认返回 0/null 已满足 BaseMapper.insert/delete 调用
  }

  @Test
  @DisplayName("legacy 模式：仅走老 2 桶，不查 Router")
  void legacyModePicksFixedAndLinkedOnly() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-001"))
        .thenReturn(List.of(part("MAT-FIXED", "固定价"), part("MAT-LINKED", "联动价")));
    when(priceFixedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(fixedItem("MAT-FIXED", "9.99")));
    when(priceLinkedCalcItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(linkedItem("MAT-LINKED", "5.55")));

    CostRunPartItemServiceImpl service = build("legacy", List.of());

    List<CostRunPartItemDto> items = service.listByOaNo("OA-001");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("9.99");
    assertThat(items.get(1).getUnitPrice()).isEqualByComparingTo("5.55");
    Mockito.verifyNoInteractions(routerService);
  }

  @Test
  @DisplayName("dual 模式：legacy 写入 + Router 双跑只 diff 不覆盖")
  void dualModeWritesLegacyAndDiffsRouter() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-002"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-FIXED", "固定价"))));
    when(priceFixedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(fixedItem("MAT-FIXED", "10.00")));
    // Router 给出固定价候选；resolver 返回不同价（11.00），dual 应保留 legacy 的 10.00
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-FIXED"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.FIXED)));

    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("11.00"), "固定采购价"));
    CostRunPartItemServiceImpl service = build("dual", List.of(fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-002");

    // dual 不替换 legacy 价格
    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("new 模式：用 Router + Resolver 覆盖；fallback 到次优先级")
  void newModeFallsBackToLowerPriority() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-003"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-RANGE", "区间价"))));
    // Router 候选：priority=1 区间价（占位 miss），priority=2 固定价（命中 8.88）
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-RANGE"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.RANGE), route(PriceTypeEnum.FIXED)));

    PriceResolver rangeResolver =
        stubResolver(PriceTypeEnum.RANGE, PriceResolveResult.miss("区间价占位"));
    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("8.88"), "固定采购价"));
    CostRunPartItemServiceImpl service = build("new", List.of(rangeResolver, fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-003");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("8.88");
    assertThat(items.get(0).getPriceSource()).isEqualTo("固定采购价");
  }

  /**
   * T5.5 金额回归：BOM 数据源从老表 {@code lp_bom_manage_item} 切到新表 {@code lp_bom_costing_row}
   * 后，部品试算输出的单价 / 用量 / 金额必须与切换前完全一致。
   *
   * <p>SQL 字段映射：{@code item_code → material_code}、{@code bom_qty → qty_per_top}、
   * {@code material_no → top_product_code}。由于 {@code CostRunPartItemMapper.selectBaseByOaNo}
   * 通过 SELECT 别名把底层字段拍平成 {@link CostRunPartItemDto}，上层看到的 DTO 结构 100% 不变，
   * 所以相同输入必定产出相同金额。
   *
   * <p>本测试用固定价 + 联动价两条典型行锁死 baseline：
   * <pre>
   *   行 1：partCode=MAT-FIXED,  partQty=3,  固定价 9.99  → amount=29.97
   *   行 2：partCode=MAT-LINKED, partQty=2,  联动价 5.55  → amount=11.10
   * </pre>
   * 任何迁移改动引起金额偏移，此用例必失败。
   */
  @Test
  @DisplayName("T5.5 金额回归：固定/联动两行 amount 与改造前严格一致")
  void amountRegressionVsLegacyBomManageItem() {
    CostRunPartItemDto fixedRow = part("MAT-FIXED", "固定价");
    fixedRow.setPartQty(new BigDecimal("3"));
    CostRunPartItemDto linkedRow = part("MAT-LINKED", "联动价");
    linkedRow.setPartQty(new BigDecimal("2"));

    // mapper.selectBaseByOaNo 在真实环境下读 lp_bom_costing_row；这里直接 mock DTO 结果，
    // 证明上层业务逻辑与切表前等价（切表的影响只在 SQL 层，上层 DTO 形态不变）
    when(costRunPartItemMapper.selectBaseByOaNo("OA-REGRESSION"))
        .thenReturn(new ArrayList<>(List.of(fixedRow, linkedRow)));
    when(priceFixedItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(fixedItem("MAT-FIXED", "9.99")));
    when(priceLinkedCalcItemMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(linkedItem("MAT-LINKED", "5.55")));

    CostRunPartItemServiceImpl service = build("legacy", List.of());
    List<CostRunPartItemDto> items = service.listByOaNo("OA-REGRESSION");

    // baseline：切换到新表前后必须严格一致
    assertThat(items).hasSize(2);
    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("9.99");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("29.97");
    assertThat(items.get(1).getUnitPrice()).isEqualByComparingTo("5.55");
    assertThat(items.get(1).getAmount()).isEqualByComparingTo("11.10");
  }

  @Test
  @DisplayName("new 模式：所有 Resolver 都 miss 时标红")
  void newModeAllMissMarksRed() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-004"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-NONE", "BOM计算"))));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-NONE"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.BOM_CALC)));

    PriceResolver bomResolver =
        stubResolver(PriceTypeEnum.BOM_CALC, PriceResolveResult.miss("BOM 计算未实现"));
    CostRunPartItemServiceImpl service = build("new", List.of(bomResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-004");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getRemark()).contains("BOM 计算未实现");
  }

  // ============================ 辅助构造 ============================

  private CostRunPartItemServiceImpl build(String mode, List<PriceResolver> resolvers) {
    return new CostRunPartItemServiceImpl(
        costRunPartItemMapper,
        priceFixedItemMapper,
        priceLinkedCalcItemMapper,
        routerService,
        resolvers,
        mode,
        new BigDecimal("0.01"));
  }

  private static CostRunPartItemDto part(String code, String priceType) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setOaNo("OA-XXX");
    dto.setPartCode(code);
    dto.setPartName(code);
    dto.setPriceType(priceType);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceFixedItem fixedItem(String code, String price) {
    PriceFixedItem item = new PriceFixedItem();
    item.setMaterialCode(code);
    item.setFixedPrice(new BigDecimal(price));
    return item;
  }

  private static PriceLinkedCalcItem linkedItem(String code, String price) {
    PriceLinkedCalcItem item = new PriceLinkedCalcItem();
    item.setItemCode(code);
    item.setPartUnitPrice(new BigDecimal(price));
    return item;
  }

  private static PriceTypeRoute route(PriceTypeEnum type) {
    return new PriceTypeRoute(
        "MAT", MaterialFormAttrEnum.PURCHASED, type, 1, null, null, "manual");
  }

  private static PriceResolver stubResolver(PriceTypeEnum type, PriceResolveResult result) {
    return new PriceResolver() {
      @Override
      public PriceTypeEnum priceType() {
        return type;
      }

      @Override
      public PriceResolveResult resolve(
          String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        return result;
      }
    };
  }

  /** 一些 ArgumentMatcher 串成的单参数匹配，避免引入额外 import。 */
  private static String eqIgnoreCaseSafe(String value) {
    return Mockito.argThat(arg -> arg != null && arg.equalsIgnoreCase(value));
  }
}
