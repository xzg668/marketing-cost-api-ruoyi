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
  private PackageComponentPriceServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PackageComponentPrice.class);
  }

  @BeforeEach
  void setUp() {
    snapshotService = mock(PackageComponentSnapshotService.class);
    materialPriceRouterService = mock(MaterialPriceRouterService.class);
    priceMapper = mock(PackageComponentPriceMapper.class);
    priceDetailMapper = mock(PackageComponentPriceDetailMapper.class);
    gapItemMapper = mock(PackageComponentGapItemMapper.class);
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
        .thenReturn(List.of(route("A")));
    when(materialPriceRouterService.listCandidates(eq("B"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-26"))))
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
  @DisplayName("缺价格类型：明细标 MISSING_ROUTE，主表不完整并写 gap")
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
        .thenReturn(List.of());

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.isComplete()).isFalse();
    assertThat(result.getPrice().getTotalPrice()).isNull();

    ArgumentCaptor<PackageComponentPriceDetail> detailCaptor =
        ArgumentCaptor.forClass(PackageComponentPriceDetail.class);
    verify(priceDetailMapper).insert(detailCaptor.capture());
    assertThat(detailCaptor.getValue().getPriceStatus()).isEqualTo("MISSING_ROUTE");
    assertThat(detailCaptor.getValue().getChildUnitPrice()).isNull();
    assertThat(detailCaptor.getValue().getChildAmount()).isNull();

    ArgumentCaptor<PackageComponentGapItem> gapCaptor =
        ArgumentCaptor.forClass(PackageComponentGapItem.class);
    verify(gapItemMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_ROUTE");
    assertThat(gapCaptor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(gapCaptor.getValue().getQuoteNo()).isEqualTo("Q-001");
    assertThat(gapCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(gapCaptor.getValue().getTopProductCode()).isEqualTo("1079900000536");
    assertThat(gapCaptor.getValue().getPackageMaterialCode()).isEqualTo("9830000026238");
    assertThat(gapCaptor.getValue().getLineNo()).isEqualTo(1);
    assertThat(gapCaptor.getValue().getChildMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getPriceType()).isNull();
    assertThat(gapCaptor.getValue().getMissingMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getMissingReason()).contains("未配价格类型路由");
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
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
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_ROUTE");
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
        .thenReturn(List.of(route("A")));

    PackagePriceResult result = service.ensurePrice(request());

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getId()).isEqualTo(506L);
    verify(priceMapper, never()).insert(any(PackageComponentPrice.class));
    verify(priceDetailMapper).delete(any(Wrapper.class));
    verify(priceDetailMapper).insert(any(PackageComponentPriceDetail.class));
  }

  @Test
  @DisplayName("同月同成品同包装父料号已有完整价格：不同 OA 直接复用，不重新取子件价")
  void reusesCompletePriceAcrossOaForSameMonthTopAndPackage() {
    service = serviceWith(resolver(Map.of()));
    PackageComponentPrice existing = new PackageComponentPrice();
    existing.setId(507L);
    existing.setPackageMaterialCode("9830000026238");
    existing.setPeriodMonth("2026-05");
    existing.setSourceTopProductCode("1079900000536");
    existing.setOaNo("OA-OLD");
    existing.setPriceStatus("PRICED");
    existing.setPriceComplete(true);
    existing.setTotalPrice(new BigDecimal("1.23000000"));
    PackageComponentPriceDetail existingDetail = new PackageComponentPriceDetail();
    existingDetail.setPriceId(507L);
    existingDetail.setLineNo(1);
    existingDetail.setChildMaterialCode("A");
    existingDetail.setPriceStatus("PRICED");
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
    when(priceDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingDetail));

    PackagePriceRequest request = request();
    request.setOaNo("OA-NEW");
    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.isComplete()).isTrue();
    assertThat(result.getPrice().getId()).isEqualTo(507L);
    assertThat(result.getPrice().getOaNo()).isEqualTo("OA-OLD");
    assertThat(result.getDetails()).hasSize(1);
    verify(snapshotService, never()).ensureSnapshot(any(PackageSnapshotRequest.class));
    verify(materialPriceRouterService, never()).listCandidates(any(), any(), any());
    verify(priceMapper, never()).insert(any(PackageComponentPrice.class));
    verify(priceMapper, never()).updateById(any(PackageComponentPrice.class));
    verify(priceDetailMapper, never()).delete(any(Wrapper.class));
    verify(priceDetailMapper, never()).insert(any(PackageComponentPriceDetail.class));
  }

  @Test
  @DisplayName("并发生成撞唯一键：重查到完整包装价后直接复用，不删明细重算")
  void reusesConcurrentCompletePriceAfterDuplicateKey() {
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
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(), List.of(concurrent));
    when(priceMapper.insert(any(PackageComponentPrice.class)))
        .thenThrow(new DuplicateKeyException("duplicate package price"));
    when(priceDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existingDetail));

    PackagePriceRequest request = request();
    request.setAsOfDate(null);
    request.setPriceAsOfTime(LocalDateTime.of(2026, 5, 26, 10, 30));
    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.isComplete()).isTrue();
    assertThat(result.getPrice().getId()).isEqualTo(508L);
    assertThat(result.getDetails()).hasSize(1);
    verify(priceDetailMapper, never()).delete(any(Wrapper.class));
    verify(priceDetailMapper, never()).insert(any(PackageComponentPriceDetail.class));
    verify(priceMapper, never()).updateById(any(PackageComponentPrice.class));
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
        .thenReturn(List.of(route("A")));
    PackagePriceRequest request = request();
    request.setForceRefresh(true);

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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
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
    when(materialPriceRouterService.listCandidates(eq("A"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
        .thenReturn(List.of(route("A")));
    when(materialPriceRouterService.listCandidates(eq("B"), eq("2026-05"), eq(LocalDate.parse("2026-05-21"))))
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
        List.of(resolver));
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
    request.setPackageMaterialCode(" 9830000026238 ");
    request.setPeriodMonth("2026-05");
    request.setQuoteNo("Q-001");
    request.setOaNo("OA-001");
    request.setTopProductCode("1079900000536");
    request.setBomPurpose("生产");
    request.setSourceType("U9");
    request.setAsOfDate(LocalDate.parse("2026-05-21"));
    request.setCalcBatchId("BATCH-001");
    return request;
  }

  private PackageSnapshotResult snapshotResult(List<PackageComponentSnapshotDetail> details) {
    return PackageSnapshotResult.of(snapshot(), details, false);
  }

  private PackageComponentSnapshot snapshot() {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(20L);
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
