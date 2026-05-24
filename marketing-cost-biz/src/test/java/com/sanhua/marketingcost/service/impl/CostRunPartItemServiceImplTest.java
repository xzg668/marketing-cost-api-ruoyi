package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
  private PackageComponentIdentifyService packageComponentIdentifyService;
  private PackageComponentPriceService packageComponentPriceService;
  private OaFormMapper oaFormMapper;

  @BeforeEach
  void setUp() {
    costRunPartItemMapper = Mockito.mock(CostRunPartItemMapper.class);
    routerService = Mockito.mock(MaterialPriceRouterService.class);
    packageComponentIdentifyService = Mockito.mock(PackageComponentIdentifyService.class);
    packageComponentPriceService = Mockito.mock(PackageComponentPriceService.class);
    oaFormMapper = Mockito.mock(OaFormMapper.class);
    // 默认给历史 apply_date，验证当前取价路由不再受 OA.apply_date 影响。
    OaForm form = new OaForm();
    form.setApplyDate(LocalDate.of(2026, 4, 20));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);
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
  @DisplayName("自制件 raw_unit_price 缺失 → 部品明细标 ERROR，单价/金额为空")
  void missingMakeRawUnitPriceShowsMissingPriceInPartItem() {
    CostRunPartItemDto p = part("MAKE-MISS-RAW");
    p.setPartQty(new BigDecimal("2"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-MAKE-MISS"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAKE-MISS-RAW"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.MAKE)));

    PriceResolver makeResolver =
        stubResolver(
            PriceTypeEnum.MAKE,
            PriceResolveResult.miss(
                "自制件原料单价缺失(raw_unit_price为空, material_code=MAKE-MISS-RAW)，未按0计算"));
    CostRunPartItemServiceImpl service = build(List.of(makeResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-MAKE-MISS");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getAmount()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("ERROR");
    assertThat(items.get(0).getRemark())
        .contains("MAKE")
        .contains("raw_unit_price为空")
        .contains("未按0计算");
  }

  @Test
  @DisplayName("Router 无候选 → priceSource=NO_ROUTE + remark 提示去配路由")
  void noRouteAvailable() {
    CostRunPartItemDto p = part("MAT-C");
    p.setShapeAttr("采购件");
    when(costRunPartItemMapper.selectBaseByOaNo("OA-003"))
        .thenReturn(new ArrayList<>(List.of(p)));
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
  @DisplayName("包装父料号 → 命中包装组件价格服务，返回父件单价并不查普通 Router")
  void packageComponentUsesPackagePriceService() {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    CostRunPartItemDto p = part("PKG-PARENT");
    p.setProductCode("TOP-001");
    p.setPartQty(new BigDecimal("3"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-PKG"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(packageComponentIdentifyService.batchIdentify(any()))
        .thenReturn(Map.of("PKG-PARENT", true));
    when(routerService.listCandidates(eqIgnoreCaseSafe("PKG-PARENT"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.FIXED)));
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class)))
        .thenReturn(packagePrice("PRICED", true, "12.345678", List.of()));

    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("1.00"), "固定采购价"));
    CostRunPartItemServiceImpl service = build(List.of(fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-PKG");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("12.345678");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("37.037034");
    assertThat(items.get(0).getPriceSource()).isEqualTo("包装组件价格");
    assertThat(items.get(0).getRemark()).isEmpty();
    verify(routerService, never()).listCandidates(eqIgnoreCaseSafe("PKG-PARENT"), anyString(), any());

    ArgumentCaptor<PackagePriceRequest> requestCaptor =
        ArgumentCaptor.forClass(PackagePriceRequest.class);
    verify(packageComponentPriceService).ensurePrice(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getPackageMaterialCode()).isEqualTo("PKG-PARENT");
    assertThat(requestCaptor.getValue().getPeriodMonth()).isEqualTo(currentPeriod);
    assertThat(requestCaptor.getValue().getAsOfDate()).isEqualTo(currentDate);
    assertThat(requestCaptor.getValue().getOaNo()).isEqualTo("OA-PKG");
    assertThat(requestCaptor.getValue().getTopProductCode()).isEqualTo("TOP-001");
    assertThat(requestCaptor.getValue().getSourceType()).isEqualTo("U9");
  }

  @Test
  @DisplayName("非包装料号 → 保持原 Router + Resolver 取价逻辑")
  void nonPackageStillUsesNormalRouter() {
    CostRunPartItemDto p = part("NORMAL-MAT");
    p.setPartQty(new BigDecimal("2"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-NORMAL"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(packageComponentIdentifyService.batchIdentify(any()))
        .thenReturn(Map.of("NORMAL-MAT", false));
    when(routerService.listCandidates(eqIgnoreCaseSafe("NORMAL-MAT"), anyString(), any()))
        .thenReturn(List.of(route(PriceTypeEnum.FIXED)));

    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("8.00"), "固定采购价"));
    CostRunPartItemServiceImpl service = build(List.of(fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-NORMAL");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("8.00");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("16.00");
    assertThat(items.get(0).getPriceSource()).isEqualTo("固定采购价");
    verify(packageComponentPriceService, never()).ensurePrice(any(PackagePriceRequest.class));
  }

  @Test
  @DisplayName("包装组件缺价/缺结构 → 当前阶段不阻断，部品行标 ERROR 并保留状态")
  void packageComponentMissingPriceDoesNotThrowButMarksTraceableError() {
    CostRunPartItemDto p = part("PKG-MISSING");
    p.setProductCode("TOP-002");
    p.setPartQty(new BigDecimal("2"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-PKG-MISS"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(packageComponentIdentifyService.batchIdentify(any()))
        .thenReturn(Map.of("PKG-MISSING", true));
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class)))
        .thenReturn(packagePrice("MISSING_CHILD_PRICE", false, null, List.of("包装组件存在子件缺价")));

    CostRunPartItemServiceImpl service = build(List.of());

    List<CostRunPartItemDto> items = service.listByOaNo("OA-PKG-MISS");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getAmount()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("ERROR");
    assertThat(items.get(0).getRemark())
        .contains("包装组件价格未完整")
        .contains("MISSING_CHILD_PRICE")
        .contains("当前阶段不阻断");
    verify(routerService, never()).listCandidates(eqIgnoreCaseSafe("PKG-MISSING"), anyString(), any());
  }

  @Test
  @DisplayName("制造件 Router 无候选 → 构造 MAKE 路由并命中制造件价格")
  void manufacturedWithoutRouteUsesMakeResolver() {
    CostRunPartItemDto p = part("MAKE-PART");
    p.setShapeAttr("制造件");
    p.setPartQty(new BigDecimal("2"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-MAKE-HIT"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAKE-PART"), anyString(), any()))
        .thenReturn(Collections.emptyList());

    PriceResolver makeResolver =
        stubResolver(PriceTypeEnum.MAKE, PriceResolveResult.hit(new BigDecimal("6.642274"), "自制件"));
    CostRunPartItemServiceImpl service = build(List.of(makeResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-MAKE-HIT");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("6.642274");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("13.284548");
    assertThat(items.get(0).getPriceSource()).isEqualTo("自制件");
    assertThat(items.get(0).getPriceType()).isEqualTo("自制件");
    assertThat(items.get(0).getMaterialShape()).isEqualTo("制造件");
    assertThat(items.get(0).getSourceSystem()).isEqualTo("cost-run-synthetic");
  }

  @Test
  @DisplayName("制造件 Router 无候选且 MAKE 未命中 → 标 ERROR 并提示缺制造件价格生成结果")
  void manufacturedWithoutRouteAndMakeMissMarksError() {
    CostRunPartItemDto p = part("MAKE-MISSING");
    p.setMaterialShape("制造件");
    when(costRunPartItemMapper.selectBaseByOaNo("OA-MAKE-MISS-RESULT"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAKE-MISSING"), anyString(), any()))
        .thenReturn(Collections.emptyList());

    PriceResolver makeResolver =
        stubResolver(PriceTypeEnum.MAKE, PriceResolveResult.miss("缺制造件价格生成结果：MAKE-MISSING"));
    CostRunPartItemServiceImpl service = build(List.of(makeResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-MAKE-MISS-RESULT");

    assertThat(items.get(0).getUnitPrice()).isNull();
    assertThat(items.get(0).getAmount()).isNull();
    assertThat(items.get(0).getPriceSource()).isEqualTo("ERROR");
    assertThat(items.get(0).getRemark())
        .contains("MAKE")
        .contains("缺制造件价格生成结果")
        .contains("MAKE-MISSING");
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

  @Test
  @DisplayName("成本试算部品路由按当前日期和当前月份取价，忽略 OA.apply_date")
  void listByOaNoUsesCurrentDateForRouter() {
    LocalDate currentDate = LocalDate.now();
    String currentPeriod = currentDate.toString().substring(0, 7);
    CostRunPartItemDto p = part("MAT-CURRENT");
    p.setPartQty(new BigDecimal("2"));
    when(costRunPartItemMapper.selectBaseByOaNo("OA-CURRENT"))
        .thenReturn(new ArrayList<>(List.of(p)));
    when(routerService.listCandidates(eqIgnoreCaseSafe("MAT-CURRENT"), eq(currentPeriod), eq(currentDate)))
        .thenReturn(List.of(route(PriceTypeEnum.FIXED)));

    PriceResolver fixedResolver =
        stubResolver(PriceTypeEnum.FIXED, PriceResolveResult.hit(new BigDecimal("5.00"), "固定采购价"));
    CostRunPartItemServiceImpl service = build(List.of(fixedResolver));

    List<CostRunPartItemDto> items = service.listByOaNo("OA-CURRENT");

    assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("5.00");
    assertThat(items.get(0).getAmount()).isEqualByComparingTo("10.00");
    verify(routerService).listCandidates(eqIgnoreCaseSafe("MAT-CURRENT"), eq(currentPeriod), eq(currentDate));
  }

  @Test
  @DisplayName("T26 包装聚合：包装组件父件金额 × 1.05，不再按子件除固定 12")
  void aggregatedPackageUsesLatestActiveRawBatch() {
    CostRunPartItemMapper partMapper = Mockito.mock(CostRunPartItemMapper.class);
    MaterialMasterMapper masterMapper = Mockito.mock(MaterialMasterMapper.class);
    MaterialMasterRawMapper rawMapper = Mockito.mock(MaterialMasterRawMapper.class);
    BomRawHierarchyMapper bomMapper = Mockito.mock(BomRawHierarchyMapper.class);

    when(partMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            storedPart("OA-1", "P-1", "PKG-PARENT", "包装组件", "12.000000"),
            storedPart("OA-1", "P-1", "NORMAL", "普通子件", "2.000000")));
    when(masterMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(rawMapper.selectPackageComponentParentsByLatestBatch(eq("包装组件"), any()))
        .thenReturn(List.of(rawParent("PKG-PARENT")));

    CostRunPartItemServiceImpl svc =
        new CostRunPartItemServiceImpl(
            partMapper,
            routerService,
            packageComponentIdentifyService,
            packageComponentPriceService,
            oaFormMapper,
            masterMapper,
            rawMapper,
            bomMapper,
            List.of());

    List<CostRunPartItemDto> items = svc.listAggregatedByOaNo("OA-1", "P-1");

    CostRunPartItemDto packageRow = items.stream()
        .filter(item -> "包装".equals(item.getPartName()))
        .findFirst()
        .orElseThrow();
    assertThat(packageRow.getAmount()).isEqualByComparingTo("12.600000");
    assertThat(packageRow.getPartCode()).isEqualTo("PKG-PARENT");
    assertThat(packageRow.getPartDrawingNo()).isEqualTo("DRW-PKG-PARENT");
    assertThat(packageRow.getUnitPrice()).isEqualByComparingTo("12.600000");
    assertThat(items).anyMatch(item -> "NORMAL".equals(item.getPartCode()));
    assertThat(items).filteredOn(item -> "PKG-PARENT".equals(item.getPartCode())).hasSize(1);
    verify(rawMapper).selectPackageComponentParentsByLatestBatch(eq("包装组件"), any());
  }

  // ============================ resolveQuoteDate ============================

  @Test
  @DisplayName("resolveQuoteDate：成本试算按当前日期取价，忽略 OA.apply_date")
  void resolveQuoteDate_useCurrentDate() {
    OaForm form = new OaForm();
    form.setApplyDate(LocalDate.of(2026, 4, 20));
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);

    CostRunPartItemServiceImpl svc = build(List.of());
    assertThat(svc.resolveQuoteDate("OA-X")).isEqualTo(LocalDate.now());
  }

  @Test
  @DisplayName("resolveQuoteDate：OA.apply_date 为空 → 当前日期")
  void resolveQuoteDate_fallbackToday() {
    OaForm form = new OaForm();
    form.setApplyDate(null);
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(form);

    CostRunPartItemServiceImpl svc = build(List.of());
    assertThat(svc.resolveQuoteDate("OA-NULL-DATE")).isEqualTo(LocalDate.now());
  }

  @Test
  @DisplayName("resolveQuoteDate：OA 不存在 → 当前日期")
  void resolveQuoteDate_oaNotFound() {
    when(oaFormMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    CostRunPartItemServiceImpl svc = build(List.of());
    assertThat(svc.resolveQuoteDate("OA-MISSING")).isEqualTo(LocalDate.now());
  }

  // ============================ 辅助构造 ============================

  private CostRunPartItemServiceImpl build(List<PriceResolver> resolvers) {
    return new CostRunPartItemServiceImpl(
        costRunPartItemMapper,
        routerService,
        packageComponentIdentifyService,
        packageComponentPriceService,
        oaFormMapper,
        Mockito.mock(com.sanhua.marketingcost.mapper.MaterialMasterMapper.class),
        Mockito.mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class),
        Mockito.mock(com.sanhua.marketingcost.mapper.BomRawHierarchyMapper.class),
        resolvers);
  }

  private static PackagePriceResult packagePrice(
      String status, boolean complete, String totalPrice, List<String> warnings) {
    PackageComponentPrice price = new PackageComponentPrice();
    price.setId(100L);
    price.setPackageMaterialCode("PKG-PARENT");
    price.setPeriodMonth("2026-05");
    price.setPriceStatus(status);
    price.setPriceComplete(complete);
    if (totalPrice != null) {
      price.setTotalPrice(new BigDecimal(totalPrice));
    }
    PackagePriceResult result = PackagePriceResult.of(price, List.of(), null);
    result.getWarnings().addAll(warnings);
    return result;
  }

  private static CostRunPartItemDto part(String code) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setOaNo("OA-XXX");
    dto.setPartCode(code);
    dto.setPartName(code);
    dto.setPartQty(BigDecimal.ONE);
    return dto;
  }

  private static CostRunPartItem storedPart(
      String oaNo, String productCode, String partCode, String partName, String amount) {
    CostRunPartItem item = new CostRunPartItem();
    item.setOaNo(oaNo);
    item.setProductCode(productCode);
    item.setPartCode(partCode);
    item.setPartName(partName);
    item.setPartDrawingNo("DRW-" + partCode);
    item.setQty(BigDecimal.ONE);
    item.setUnitPrice(new BigDecimal(amount));
    item.setAmount(new BigDecimal(amount));
    return item;
  }

  private static MaterialMasterRaw rawParent(String materialCode) {
    MaterialMasterRaw parent = new MaterialMasterRaw();
    parent.setMaterialCode(materialCode);
    parent.setMainCategoryName("包装组件");
    parent.setImportBatchId("u9-new");
    parent.setActiveFlag(1);
    return parent;
  }

  private static PriceTypeRoute route(PriceTypeEnum priceType) {
    return new PriceTypeRoute("M", MaterialFormAttrEnum.PURCHASED, priceType, 1, null, null, "manual", priceType.getDbText());
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
