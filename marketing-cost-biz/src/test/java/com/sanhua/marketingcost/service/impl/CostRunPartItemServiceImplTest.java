package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 部品试算服务单测 —— v1.1 (T04) 重写版。
 *
 * <p>历史：原本测 legacy / dual / new 三种模式。T04 起 legacy/dual 物理删除（老结果都是
 * 测试期造的数据，无生产价值），仅保留 Router + Resolver 单一路径。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Router 多候选时按 priority 升序 fallback</li>
 *   <li>所有 Resolver 都 miss → priceSource/remark 标红</li>
 *   <li>Router 无候选 → 标 "Router 无候选"</li>
 *   <li>命中后金额 = unitPrice × partQty 正确计算</li>
 * </ul>
 */
class CostRunPartItemServiceImplTest {

  private CostRunPartItemMapper costRunPartItemMapper;
  private MaterialPriceRouterService routerService;

  @BeforeEach
  void setUp() {
    costRunPartItemMapper = Mockito.mock(CostRunPartItemMapper.class);
    routerService = Mockito.mock(MaterialPriceRouterService.class);
  }

  @Test
  @DisplayName("Router 多候选 → 按 priority 顺序 fallback 到首个命中桶")
  void routerFallsBackToLowerPriority() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-001"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-A"))));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-A"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.RANGE), route(PriceTypeEnum.FIXED)));

    PriceResolver rangeResolver =
        stubResolver(PriceTypeEnum.RANGE, PriceResolveResult.miss("区间价占位"));
    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("8.88"), "固定采购价"));
    CostRunPartItemServiceImpl service = build(List.of(rangeResolver, fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-001");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("8.88");
    assertThat(items.get(0).getPriceSource()).isEqualTo("固定采购价");
    // hit 后 remark 应为空字符串（PriceResolveResult.hit 工厂约定），不应残留 NO_ROUTE / ERROR
    assertThat(items.get(0).getRemark()).isEmpty();
  }

  @Test
  @DisplayName("所有 Resolver 都 miss → priceSource=ERROR + remark 含桶名 + 子 miss 原因")
  void allResolversMissMarksRed() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-002"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-B"))));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-B"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.MAKE)));

    PriceResolver makeResolver =
        stubResolver(PriceTypeEnum.MAKE, PriceResolveResult.miss("MAKE Resolver 未实现"));
    CostRunPartItemServiceImpl service = build(List.of(makeResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-002");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("ERROR");
    assertThat(items.get(0).getRemark()).contains("MAKE");
    assertThat(items.get(0).getRemark()).contains("MAKE Resolver 未实现");
  }

  @Test
  @DisplayName("Router 无候选 → priceSource=NO_ROUTE + remark 提示去配路由")
  void noRouteAvailable() {
    when(costRunPartItemMapper.selectBaseByOaNo("OA-003"))
        .thenReturn(new ArrayList<>(List.of(part("MAT-C"))));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-C"), anyString(), any()))
        .thenReturn(Collections.emptyList());

    CostRunPartItemServiceImpl service = build(List.of());
    List<CostRunPartItemDto> items = service.listByOaNo("OA-003");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("NO_ROUTE");
    assertThat(items.get(0).getRemark()).contains("未配价格类型路由");
    assertThat(items.get(0).getRemark()).contains("MAT-C");
  }

  @Test
  @DisplayName("partCode 为空 → priceSource=ERROR + remark='partCode 为空'，不查 Router 也不抛异常")
  void emptyPartCodeMarksError() {
    CostRunPartItemDto p = part("");
    when(costRunPartItemMapper.selectBaseByOaNo("OA-EMPTY"))
        .thenReturn(new ArrayList<>(List.of(p)));

    CostRunPartItemServiceImpl service = build(List.of());
    List<CostRunPartItemDto> items = service.listByOaNo("OA-EMPTY");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("ERROR");
    assertThat(items.get(0).getRemark()).contains("partCode 为空");
    // 不应触发 Router 查询
    Mockito.verifyNoInteractions(routerService);
  }

  @Test
  @DisplayName("命中后金额 = unitPrice × partQty 正确计算")
  void amountCalculation() {
    CostRunPartItemDto p = part("MAT-D");
    p.setPartQty(new BigDecimal("3"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-004"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-D"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.FIXED)));

    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("9.99"), "固定采购价"));
    CostRunPartItemServiceImpl service = build(List.of(fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-004");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("9.99");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("29.97");  // 9.99 × 3
  }

  // ============================ 辅助构造 ============================

  private CostRunPartItemServiceImpl build(List<PriceResolver> resolvers) {
    return new CostRunPartItemServiceImpl(costRunPartItemMapper, routerService, resolvers);
  }

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setOaNo("OA-XXX");
    dto.setPartCode(code);
    dto.setPartName(code);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static PriceTypeRoute route(PriceTypeEnum priceType) {
    return new PriceTypeRoute("M", MaterialFormAttrEnum.PURCHASED, priceType, 1, null, null, "manual");
  }

  private static PriceResolver stubResolver(PriceTypeEnum bucket, PriceResolveResult fixed) {
    return new PriceResolver() {
      @Override
      public PriceTypeEnum priceType() {
        return bucket;
      }
      @Override
      public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        return fixed;
      }
    };
  }

  /** 兼容大小写差异的 String matcher（路由表 material_code 大小写 fluffy） */
  private static String eqIgnoreCaseSafe(String expected) {
    return argThat(
        actual -> actual != null && actual.equalsIgnoreCase(expected));
  }
}
