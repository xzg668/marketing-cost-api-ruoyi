package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

@DisplayName("PackageComponentPriceServiceImpl")
class PackageComponentPriceServiceImplTest {

  private PackageComponentSnapshotService snapshotService;
  private MaterialPriceRouterService materialPriceRouterService;
  private PackageComponentPriceMapper priceMapper;
  private PackageComponentPriceDetailMapper priceDetailMapper;
  private PackageComponentGapItemMapper gapItemMapper;
  private com.sanhua.marketingcost.service.LinkedPriceEnsureService linkedPrices;
  private PackageComponentPriceServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PackageComponentPrice.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentPriceDetail.class);
    TableInfoHelper.initTableInfo(assistant, PackageComponentGapItem.class);
  }

  @BeforeEach
  void setUp() {
    snapshotService = mock(PackageComponentSnapshotService.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    priceMapper = mock(PackageComponentPriceMapper.class);
    priceDetailMapper = mock(PackageComponentPriceDetailMapper.class);
    gapItemMapper = mock(PackageComponentGapItemMapper.class);
    linkedPrices = mock(com.sanhua.marketingcost.service.LinkedPriceEnsureService.class);
  }

  @Test
  @DisplayName("全部子件取价成功：逐行写明细并汇总包装组件价格")
  void pricesAllChildrenSuccessfully() {
    service = serviceWith(resolver(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("3.000000"), "固定采购价"),
        "B", PriceResolveResult.hit(new BigDecimal("1.500000"), "固定采购价"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"), detail(102L, 2, "B", "4.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(500L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    when(materialPriceRouterService.prepareCandidates(eq("B"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("B")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.isComplete()).isTrue();
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("12.000000000000");

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper, times(2)).insert(detailCaptor.capture());
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getPriceStatus)
        .containsExactly("PRICED", "PRICED");
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getChildAmount)
        .containsExactly(new BigDecimal("6.000000000000"), new BigDecimal("6.000000000000"));
    verify(gapItemMapper, never()).insert(any(PackageComponentGapItem.class));
    verify(priceDetailMapper, never()).delete(any(Wrapper.class));
  }

  @Test
  @DisplayName("第四步包装价格只读计算：返回汇总和明细但不写快照、价格或缺口")
  void calculatesPackagePriceWithoutPersistence() {
    service = serviceWith(resolver(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("3.000000"), "固定采购价"),
        "B", PriceResolveResult.hit(new BigDecimal("1.500000"), "固定采购价"))));
    when(snapshotService.previewSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(
            detail(101L, 1, "A", "2.000000"),
            detail(102L, 2, "B", "4.000000"))));
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    when(materialPriceRouterService.listCandidates(eq("B"), eq("2026-05"), any(LocalDate.class), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("B")));

    PackagePriceResult result = service.calculatePrice(request());

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.isComplete()).isTrue();
    assertThat(result.getPrice().getId()).isNull();
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("12.000000000000");
    assertThat(result.getDetails()).hasSize(2);
    verify(snapshotService, never()).ensureSnapshot(any());
    verify(priceMapper, never()).insert(any(PackageComponentPrice.class));
    verify(priceMapper, never()).updateById(any(PackageComponentPrice.class));
    verify(priceDetailMapper, never()).insert(any(PackageComponentPriceDetail.class));
    verify(priceDetailMapper, never()).delete(any());
    verify(gapItemMapper, never()).insert(any(PackageComponentGapItem.class));
    verify(gapItemMapper, never()).updateById(any(PackageComponentGapItem.class));
  }

  @Test
  @DisplayName("T22：月度包装件按 price_as_of_time 传递给子件取价上下文")
  void monthlyPriceAsOfTimePassedToChildResolver() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 26, 10, 30);
    AtomicReference<CostRunContext> capturedContext = new AtomicReference<>();
    service = serviceWith(contextAwareResolver(
        "A",
        PriceResolveResult.hit(new BigDecimal("3.000000"), "固定采购价"),
        capturedContext));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(510L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-26")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    PackagePriceRequest request = request();
    request.setAsOfDate(null);
    request.setPriceAsOfTime(priceAsOfTime);

    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(capturedContext.get()).isNotNull();
    assertThat(capturedContext.get().getPricingMonth()).isEqualTo("2026-05");
    assertThat(capturedContext.get().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    ArgumentCaptor<PackageSnapshotRequest> snapshotCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(snapshotService).ensureSnapshot(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getAsOfDate()).isEqualTo(LocalDate.parse("2026-05-26"));
    ArgumentCaptor<PackageComponentPrice> priceCaptor =
        ArgumentCaptor.forClass(PackageComponentPrice.class);
    verify(priceMapper).insert(priceCaptor.capture());
    assertThat(priceCaptor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
  }

  @Test
  @DisplayName("无可用价源：明细标 MISSING_PRICE，主表不完整并写 gap")
  void marksMissingRoute() {
    service = serviceWith(resolver(Map.of()));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(501L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of());

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.isComplete()).isFalse();
    assertThat(result.getPrice().getTotalPrice()).isNull();

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper).insert(detailCaptor.capture());
    assertThat(detailCaptor.getValue().getPriceStatus()).isEqualTo("MISSING_PRICE");
    assertThat(detailCaptor.getValue().getChildUnitPrice()).isNull();
    assertThat(detailCaptor.getValue().getChildAmount()).isNull();

    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(gapCaptor.getValue().getQuoteNo()).isEqualTo("Q-001");
    assertThat(gapCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(gapCaptor.getValue().getTopProductCode()).isEqualTo("1079900000536");
    assertThat(gapCaptor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(gapCaptor.getValue().getLineNo()).isEqualTo(1);
    assertThat(gapCaptor.getValue().getChildMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getPriceType()).isNull();
    assertThat(gapCaptor.getValue().getMissingMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getMissingReason()).contains("没有可用价格来源");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("有价格类型但价表取不到：明细标 MISSING_PRICE 并保留原因")
  void marksMissingPriceWhenResolverMisses() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.miss("固定价无记录"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(502L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.getPrice().getTotalPrice()).isNull();

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper).insert(detailCaptor.capture());
    assertThat(detailCaptor.getValue().getPriceStatus()).isEqualTo("MISSING_PRICE");
    assertThat(detailCaptor.getValue().getMissingReason()).contains("固定价无记录");
    assertThat(detailCaptor.getValue().getChildAmount()).isNull();

    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getLineNo()).isEqualTo(1);
    assertThat(gapCaptor.getValue().getChildMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getPriceType()).isEqualTo("固定采购价");
    assertThat(gapCaptor.getValue().getMissingMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getMissingReason()).contains("固定价无记录");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("包装子件供货比率审核原因传递给报价缺口")
  void keepsSupplierRatioFailureCodeForPackageChild() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.miss(
        "SUPPLIER_RATIO_REVIEW_REQUIRED", "供货比率不足，请确认是否有新增审核"))));
    when(snapshotService.previewSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), any(LocalDate.class),
        org.mockito.Mockito.any(), org.mockito.Mockito.any())).thenReturn(List.of(route("A")));

    PackagePriceResult result = service.calculatePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.getDetails()).singleElement().satisfies(row -> {
      assertThat(row.getPriceType()).isEqualTo("FIXED");
      assertThat(row.getFailureCode()).isEqualTo("SUPPLIER_RATIO_REVIEW_REQUIRED");
    });
  }

  @Test
  @DisplayName("缺价重复生成：复用既有 gap，不重复插入")
  void upsertsExistingGapWhenRegenerating() {
    service = serviceWith(resolver(Map.of()));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(505L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of());
    PackageComponentGapItem existingGap = new PackageComponentGapItem();
    existingGap.setId(800L);
    when(gapItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingGap));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    verify(gapItemMapper, never()).insert(any(PackageComponentGapItem.class));
    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).updateById(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getId()).isEqualTo(800L);
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getLineNo()).isEqualTo(1);
    assertThat(gapCaptor.getValue().getChildMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("重复生成同一月份同一顶层产品：未完整价格行复用主表并重算明细")
  void reusesExistingPriceRowWhenRegeneratingSameContext() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    PackageComponentPrice existing = new PackageComponentPrice();
    existing.setId(506L);
    existing.setPackageMaterialCode("9830000026238");
    existing.setPeriodMonth("2026-05");
    existing.setSourceTopProductCode("1079900000536");
    existing.setPriceStatus("MISSING_CHILD_PRICE");
    existing.setPriceComplete(false);
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getId()).isEqualTo(506L);
    verify(priceMapper, never()).insert(any(PackageComponentPrice.class));
    verify(priceDetailMapper).delete(any(Wrapper.class));
    verify(priceDetailMapper).insert(any(PackageComponentPriceDetail.class));
  }

  @Test
  @DisplayName("同月同组件的新OA必须按本次输入重新计算子件")
  void newQuoteRecalculatesChildrenInsteadOfReusingOtherQuotePrice() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.hit(new BigDecimal("2"), "固定采购价"))));
    var existing = new PackageComponentPrice(); existing.setId(507L);
    existing.setPriceStatus("PRICED"); existing.setPriceComplete(true); existing.setTotalPrice(new BigDecimal("1.23"));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
    when(snapshotService.ensureSnapshot(any())).thenReturn(snapshotResult(List.of(detail(101L,1,"A","2"))));
    when(materialPriceRouterService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any()))
        .thenReturn(List.of(route("A")));
    var request = request(); request.setOaNo("OA-NEW");
    var result = service.ensurePrice(request);
    assertThat(result.getPrice().getId()).isEqualTo(507L);
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("4");
    verify(materialPriceRouterService).prepareCandidates(eq("A"), org.mockito.Mockito.any(), any(), any(), any(), any());
    verify(priceDetailMapper).delete(any(Wrapper.class));
    verify(priceDetailMapper).insert(any(PackageComponentPriceDetail.class));
  }

  @Test
  @DisplayName("不同组织已有完整包装价时不能跨组织复用")
  void plateRequestDoesNotReuseCommercialCompletePrice() {
    service = serviceWith(resolver(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价"))));
    PackageComponentPrice commercial = new PackageComponentPrice();
    commercial.setId(517L);
    commercial.setPriceOrgCode("210");
    commercial.setPackageMaterialCode("9830000026238");
    commercial.setPeriodMonth("2026-05");
    commercial.setSourceTopProductCode("1079900000536");
    commercial.setPriceStatus("PRICED");
    commercial.setPriceComplete(true);
    commercial.setTotalPrice(new BigDecimal("1.23000000"));
    when(priceMapper.selectList(any(Wrapper.class)))
        .thenAnswer(
            invocation -> {
              AbstractWrapper<?, ?, ?> wrapper = invocation.getArgument(0);
              boolean plateQuery = hasParamValue(wrapper, "220");
              return plateQuery ? List.of() : List.of(commercial);
            });
    PackageComponentSnapshot plateSnapshot = snapshot();
    plateSnapshot.setPriceOrgCode("220");
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(PackageSnapshotResult.of(
            plateSnapshot, List.of(detail(101L, 1, "A", "2.000000")), true));
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(518L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    PackagePriceRequest request = request();
    request.setPriceOrgCode("220");

    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getId()).isEqualTo(518L);
    ArgumentCaptor<PackageSnapshotRequest> snapshotCaptor =
        ArgumentCaptor.forClass(PackageSnapshotRequest.class);
    verify(snapshotService).ensureSnapshot(snapshotCaptor.capture());
    assertThat(snapshotCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
    ArgumentCaptor<PackageComponentPrice> priceCaptor =
        ArgumentCaptor.forClass(PackageComponentPrice.class);
    verify(priceMapper).insert(priceCaptor.capture());
    assertThat(priceCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
  }

  @Test
  @DisplayName("并发生成撞唯一键：重查到完整包装价后直接复用，不删明细重算")
  void recalculatesChildrenAfterConcurrentPriceInsert() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    PackageComponentPrice concurrent = new PackageComponentPrice();
    concurrent.setId(508L);
    concurrent.setPackageMaterialCode("9830000026238");
    concurrent.setPeriodMonth("2026-05");
    concurrent.setSourceTopProductCode("1079900000536");
    concurrent.setPriceAsOfTime(LocalDateTime.of(2026, 5, 26, 10, 30));
    concurrent.setPriceStatus("PRICED");
    concurrent.setPriceComplete(true);
    concurrent.setTotalPrice(new BigDecimal("1.23000000"));
    PackageComponentPriceDetail existingDetail = new PackageComponentPriceDetail();
    existingDetail.setPriceId(508L);
    existingDetail.setLineNo(1);
    existingDetail.setChildMaterialCode("A");
    existingDetail.setPriceStatus("PRICED");
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(concurrent));
    when(priceMapper.insert(any(PackageComponentPrice.class)))
        .thenThrow(new DuplicateKeyException("duplicate package price"));
    when(priceDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingDetail));

    when(materialPriceRouterService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any()))
        .thenReturn(List.of(route("A")));
    PackagePriceRequest request = request();
    request.setAsOfDate(null);
    request.setPriceAsOfTime(LocalDateTime.of(2026, 5, 26, 10, 30));
    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.isComplete()).isTrue();
    assertThat(result.getPrice().getId()).isEqualTo(508L);
    assertThat(result.getDetails()).hasSize(1);
    verify(priceDetailMapper).delete(any(Wrapper.class));
    verify(priceDetailMapper).insert(any(PackageComponentPriceDetail.class));
    verify(priceMapper, times(2)).updateById(any(PackageComponentPrice.class));
  }

  @Test
  @DisplayName("报价包装重算：只更新同一取价时点的包装价行，不能改写其他时点行")
  void forceRefreshDoesNotUpdateDifferentPriceAsOfRow() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    PackageComponentPrice otherAsOf = new PackageComponentPrice();
    otherAsOf.setId(509L);
    otherAsOf.setPackageMaterialCode("9830000026238");
    otherAsOf.setPeriodMonth("2026-05");
    otherAsOf.setSourceTopProductCode("1079900000536");
    otherAsOf.setPriceAsOfTime(LocalDateTime.of(2026, 5, 20, 0, 0));
    otherAsOf.setPriceStatus("PRICED");
    otherAsOf.setPriceComplete(true);
    otherAsOf.setTotalPrice(new BigDecimal("9.99000000"));
    when(priceMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
      Wrapper<?> wrapper = invocation.getArgument(0);
      String sql = wrapper == null ? "" : wrapper.getCustomSqlSegment();
      return sql.contains("price_as_of_time") ? List.of() : List.of(otherAsOf);
    });
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(510L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    PackagePriceRequest request = request();

    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getId()).isEqualTo(510L);
    verify(priceMapper).insert(any(PackageComponentPrice.class));
  }

  @Test
  @DisplayName("重复子件：不合并，按两行分别计算金额")
  void keepsDuplicateChildRowsWhenPricing() {
    service = serviceWith(resolver(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "1.000000"), detail(102L, 2, "A", "3.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(503L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("8.000000000000");

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper, times(2)).insert(detailCaptor.capture());
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getChildMaterialCode)
        .containsExactly("A", "A");
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getLineNo)
        .containsExactly(1, 2);
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getChildAmount)
        .containsExactly(new BigDecimal("2.000000000000"), new BigDecimal("6.000000000000"));
  }

  @Test
  @DisplayName("部分缺价：不能把缺价行按 0 汇总成正常价格")
  void doesNotSilentlyZeroMissingChildPrice() {
    service = serviceWith(resolver(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("5.000000"), "固定采购价"),
        "B", PriceResolveResult.miss("固定价无记录"))));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"), detail(102L, 2, "B", "4.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(504L);
      return 1;
    });
    when(materialPriceRouterService.prepareCandidates(eq("A"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("A")));
    when(materialPriceRouterService.prepareCandidates(eq("B"), org.mockito.Mockito.any(), eq("2026-05"), eq(LocalDate.parse("2026-05-21")), org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(List.of(route("B")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.isComplete()).isFalse();
    assertThat(result.getPrice().getTotalPrice()).isNull();

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper, times(2)).insert(detailCaptor.capture());
    assertThat(detailCaptor.getAllValues()).extracting(PackageComponentPriceDetail::getPriceStatus)
        .containsExactly("PRICED", "MISSING_PRICE");
    assertThat(detailCaptor.getAllValues().get(1).getChildAmount()).isNull();
  }

  private PackageComponentPriceServiceImpl serviceWith(PriceResolver resolver) {
    return new PackageComponentPriceServiceImpl(
        snapshotService,
        materialPriceRouterService,
        priceMapper,
        priceDetailMapper,
        gapItemMapper,
        List.of(resolver), linkedPrices);
  }

  private PriceResolver resolver(Map<String, PriceResolveResult> results) {
    return new PriceResolver() {
      @Override
      public PriceTypeEnum priceType() {
        return PriceTypeEnum.FIXED;
      }

      @Override
      public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        return results.getOrDefault(item.getPartCode(), PriceResolveResult.miss("无测试价格"));
      }
    };
  }

  @Test
  void linkedChildUsesCurrentQuoteAndFinancialInputsForPreviewAndPreparation() {
    var resolver = mock(PriceResolver.class);
    when(resolver.priceType()).thenReturn(PriceTypeEnum.LINKED);
    service = serviceWith(resolver);
    var route = new PriceTypeRoute("A", MaterialFormAttrEnum.PURCHASED, PriceTypeEnum.LINKED,
        1, null, null, "SYSTEM", "联动价");
    var snapshot = snapshotResult(List.of(detail(101L, 1, "A", "2")));
    when(snapshotService.previewSnapshot(any())).thenReturn(snapshot);
    when(snapshotService.ensureSnapshot(any())).thenReturn(snapshot);
    when(materialPriceRouterService.listCandidates(any(), any(), any(), any(), any())).thenReturn(List.of(route));
    when(materialPriceRouterService.prepareCandidates(any(), org.mockito.Mockito.any(), any(), any(), any(), any())).thenReturn(List.of(route));
    var row = new com.sanhua.marketingcost.entity.PriceLinkedCalcItem();
    row.setItemCode("A"); row.setPartUnitPrice(new BigDecimal("99.12345678"));
    row.setInputSnapshotJson("{\"ruleVersion\":3,\"customerCu\":90000.123456789}");
    when(linkedPrices.calculate(any())).thenReturn(List.of(row));
    var prepared = new com.sanhua.marketingcost.dto.LinkedPriceEnsureResult();
    prepared.getItems().put("A", row);
    when(linkedPrices.ensure(any())).thenReturn(prepared);
    when(priceMapper.selectList(any())).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(call -> { ((PackageComponentPrice) call.getArgument(0)).setId(500L); return 1; });
    var request = request();
    request.setPriceScenarioType(com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("90.12")));
    request.setSupplierPriceReviewContext(new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(19L));
    var preview = service.calculatePrice(request);
    assertThat(preview.getPrice().getTotalPrice()).isEqualByComparingTo("198.24691356");
    assertThat(preview.getDetails().getFirst().getInputSnapshotJson()).isEqualTo(row.getInputSnapshotJson());
    verify(linkedPrices, never()).ensure(any());
    var result = service.ensurePrice(request);
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("198.24691356");
    var captor = ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest.class);
    verify(linkedPrices).ensure(captor.capture());
    var actual = captor.getValue();
    assertThat(actual.getOaNo()).isEqualTo("OA-001");
    assertThat(actual.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(actual.getPriceOrgCode()).isEqualTo("210");
    assertThat(actual.getPricingMonth()).isEqualTo("2026-05");
    assertThat(actual.getPriceScenarioType()).isEqualTo(request.getPriceScenarioType());
    assertThat(actual.getVariableOverrides()).containsEntry("Cu", new BigDecimal("90.12"));
    assertThat(actual.getSupplierPriceReviewContext()).isSameAs(request.getSupplierPriceReviewContext());
    var checked = ArgumentCaptor.forClass(com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest.class);
    verify(linkedPrices).calculate(checked.capture());
    assertThat(checked.getValue().getSupplierPriceReviewContext()).isSameAs(request.getSupplierPriceReviewContext());
    verify(resolver, never()).resolve(any(), any(), any(), any());
  }

  private PriceResolver contextAwareResolver(
      String materialCode,
      PriceResolveResult result,
      AtomicReference<CostRunContext> capturedContext) {
    return new PriceResolver() {
      @Override
      public PriceTypeEnum priceType() {
        return PriceTypeEnum.FIXED;
      }

      @Override
      public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
        return PriceResolveResult.miss("必须走带上下文的取价入口");
      }

      @Override
      public PriceResolveResult resolve(
          String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
        capturedContext.set(context);
        return materialCode.equals(item.getPartCode()) ? result : PriceResolveResult.miss("无测试价格");
      }
    };
  }

  private PackagePriceRequest request() {
    PackagePriceRequest request = new PackagePriceRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setPackageMaterialCode(" 9830000026238 ");
    request.setPeriodMonth("2026-05");
    request.setQuoteNo("Q-001");
    request.setOaNo("OA-001");
    request.setTopProductCode("1079900000536");
    request.setPriceOrgCode("210");
    request.setBomPurpose("生产");
    request.setSourceType("U9");
    request.setAsOfDate(LocalDate.parse("2026-05-21"));
    request.setCalcBatchId("BATCH-001");
    return request;
  }

  private boolean hasParamValue(AbstractWrapper<?, ?, ?> wrapper, String expectedValue) {
    if (wrapper == null) {
      return false;
    }
    wrapper.getSqlSegment();
    return wrapper.getParamNameValuePairs().values().stream()
        .map(String::valueOf)
        .anyMatch(expectedValue::equals);
  }

  private PackageSnapshotResult snapshotResult(List<PackageComponentSnapshotDetail> details) {
    return PackageSnapshotResult.of(snapshot(), details, false);
  }

  private PackageComponentSnapshot snapshot() {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(20L);
    snapshot.setPriceOrgCode("210");
    snapshot.setPackageMaterialCode("9830000026238");
    snapshot.setPackageMaterialName("包装组件");
    snapshot.setPeriodMonth("2026-05");
    snapshot.setStatus("NORMAL");
    snapshot.setSourceTopProductCode("1079900000536");
    return snapshot;
  }

  private PackageComponentSnapshotDetail detail(Long id, int lineNo, String childCode, String qty) {
    PackageComponentSnapshotDetail detail = new PackageComponentSnapshotDetail();
    detail.setId(id);
    detail.setSnapshotId(20L);
    detail.setPackageMaterialCode("9830000026238");
    detail.setPeriodMonth("2026-05");
    detail.setLineNo(lineNo);
    detail.setChildMaterialCode(childCode);
    detail.setChildMaterialName("子件" + childCode);
    detail.setChildMaterialSpec("SPEC-" + childCode);
    detail.setChildShapeAttr("采购件");
    detail.setQtyPerParent(new BigDecimal(qty));
    return detail;
  }

  private PriceTypeRoute route(String materialCode) {
    return new PriceTypeRoute(
        materialCode,
        MaterialFormAttrEnum.PURCHASED,
        PriceTypeEnum.FIXED,
        1,
        null,
        null,
        "manual",
        "固定采购价");
  }
}
