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
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.monthly.*;
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
  private PackageComponentPriceMapper priceMapper;
  private PackageComponentPriceDetailMapper priceDetailMapper;
  private PackageComponentGapItemMapper gapItemMapper;
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
    priceMapper = mock(PackageComponentPriceMapper.class);
    priceDetailMapper = mock(PackageComponentPriceDetailMapper.class);
    gapItemMapper = mock(PackageComponentGapItemMapper.class);
  }

  @Test
  @DisplayName("全部子件取价成功：逐行写明细并汇总包装组件价格")
  void pricesAllChildrenSuccessfully() {
    service = serviceWith(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("3.000000"), "固定采购价"),
        "B", PriceResolveResult.hit(new BigDecimal("1.500000"), "固定采购价")));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"), detail(102L, 2, "B", "4.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(500L);
      return 1;
    });

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
    service = serviceWith(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("3.000000"), "固定采购价"),
        "B", PriceResolveResult.hit(new BigDecimal("1.500000"), "固定采购价")));
    when(snapshotService.previewSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(
            detail(101L, 1, "A", "2.000000"),
            detail(102L, 2, "B", "4.000000"))));

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
  @DisplayName("板换原表：子件用量只除包装组件底数6，不再除子件行的底数")
  void workbookPackagingUsesComponentBaseOnce() {
    service = serviceWith(Map.of(
        "PAD", PriceResolveResult.hit(new BigDecimal("0.779800"), "固定采购价"),
        "BOX", PriceResolveResult.hit(new BigDecimal("6.931300"), "固定采购价"),
        "H1", PriceResolveResult.hit(new BigDecimal("1.961000"), "固定采购价"),
        "H2", PriceResolveResult.hit(new BigDecimal("1.231100"), "固定采购价"),
        "H3", PriceResolveResult.hit(new BigDecimal("2.462200"), "固定采购价")));
    var rows = List.of(detail(201L, 1, "PAD", "2"), detail(202L, 2, "BOX", "1"),
        detail(203L, 3, "H1", "1"), detail(204L, 4, "H2", "3"), detail(205L, 5, "H3", "3"));
    rows.forEach(row -> row.setChildParentBaseQty(row.getQtyPerParent()));
    var snapshot = snapshotResult(rows);
    snapshot.getSnapshot().setPackageParentBaseQty(new BigDecimal("6"));
    when(snapshotService.previewSnapshot(any(PackageSnapshotRequest.class))).thenReturn(snapshot);

    var result = service.calculatePrice(request());

    assertThat(result.isComplete()).isTrue();
    assertThat(result.getDetails().getFirst().getChildAmount()).isEqualByComparingTo("1.559600");
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("3.58863333");
    assertThat(result.getPrice().getTotalPrice().multiply(new BigDecimal("1.05"))
        .setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("3.768065");
  }

  @Test
  @DisplayName("T22：月度包装件按 price_as_of_time 传递给子件取价上下文")
  void monthlyPriceAsOfTimePassedToChildResolver() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 26, 10, 30);
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("3.000000"), "月价")));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(510L);
      return 1;
    });
    PackagePriceRequest request = request();
    request.setAsOfDate(null);
    request.setPriceAsOfTime(priceAsOfTime);

    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(capturedContext.get()).isNotNull();
    assertThat(capturedContext.get().month()).isEqualTo("2026-05");
    assertThat(capturedContext.get().priceAsOfTime()).isEqualTo(priceAsOfTime);
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
    service = serviceWith(Map.of());
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(501L);
      return 1;
    });

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
    service = serviceWith(Map.of("A", PriceResolveResult.miss("固定价无记录")));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(502L);
      return 1;
    });

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
    assertThat(gapCaptor.getValue().getPriceType()).isNull();
    assertThat(gapCaptor.getValue().getMissingMaterialCode()).isEqualTo("A");
    assertThat(gapCaptor.getValue().getMissingReason()).contains("固定价无记录");
    assertThat(gapCaptor.getValue().getStatus()).isEqualTo("PENDING_MAINTAIN");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("NOT_PUSHED");
  }

  @Test
  @DisplayName("包装子件供货比率审核原因传递给报价缺口")
  void keepsSupplierRatioFailureCodeForPackageChild() {
    service = serviceWith(Map.of("A", PriceResolveResult.miss(
        "SUPPLIER_RATIO_REVIEW_REQUIRED", "供货比率不足，请确认是否有新增审核")));
    when(snapshotService.previewSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));

    PackagePriceResult result = service.calculatePrice(request());

    assertThat(result.getStatus()).isEqualTo("MISSING_CHILD_PRICE");
    assertThat(result.getDetails()).singleElement().satisfies(row -> {
      assertThat(row.getPriceType()).isNull();
      assertThat(row.getFailureCode()).isEqualTo("WAITING");
    });
  }

  @Test
  @DisplayName("缺价重复生成：复用既有 gap，不重复插入")
  void upsertsExistingGapWhenRegenerating() {
    service = serviceWith(Map.of());
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(505L);
      return 1;
    });
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
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价")));
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
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("2"), "固定采购价")));
    var existing = new PackageComponentPrice(); existing.setId(507L);
    existing.setPriceStatus("PRICED"); existing.setPriceComplete(true); existing.setTotalPrice(new BigDecimal("1.23"));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
    when(snapshotService.ensureSnapshot(any())).thenReturn(snapshotResult(List.of(detail(101L,1,"A","2"))));
    var request = request(); request.setOaNo("OA-NEW");
    var result = service.ensurePrice(request);
    assertThat(result.getPrice().getId()).isEqualTo(507L);
    assertThat(result.getPrice().getTotalPrice()).isEqualByComparingTo("4");
    assertThat(capturedContext.get().productCode()).isEqualTo("1079900000536");
    verify(priceDetailMapper).delete(any(Wrapper.class));
    verify(priceDetailMapper).insert(any(PackageComponentPriceDetail.class));
  }

  @Test
  @DisplayName("不同组织已有完整包装价时不能跨组织复用")
  void plateRequestDoesNotReuseCommercialCompletePrice() {
    service = serviceWith(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价")));
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
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价")));
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
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价")));
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
    PackagePriceRequest request = request();

    PackagePriceResult result = service.ensurePrice(request);

    assertThat(result.getStatus()).isEqualTo("PRICED");
    assertThat(result.getPrice().getId()).isEqualTo(510L);
    verify(priceMapper).insert(any(PackageComponentPrice.class));
  }

  @Test
  @DisplayName("重复子件：不合并，按两行分别计算金额")
  void keepsDuplicateChildRowsWhenPricing() {
    service = serviceWith(Map.of("A", PriceResolveResult.hit(new BigDecimal("2.000000"), "固定采购价")));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "1.000000"), detail(102L, 2, "A", "3.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(503L);
      return 1;
    });

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
    service = serviceWith(Map.of(
        "A", PriceResolveResult.hit(new BigDecimal("5.000000"), "固定采购价"),
        "B", PriceResolveResult.miss("固定价无记录")));
    when(snapshotService.ensureSnapshot(any(PackageSnapshotRequest.class)))
        .thenReturn(snapshotResult(List.of(detail(101L, 1, "A", "2.000000"), detail(102L, 2, "B", "4.000000"))));
    when(priceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(priceMapper.insert(any(PackageComponentPrice.class))).thenAnswer(invocation -> {
      PackageComponentPrice price = invocation.getArgument(0);
      price.setId(504L);
      return 1;
    });

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

  private final AtomicReference<QuotationPriceSessions.Request> capturedContext = new AtomicReference<>();

  private PackageComponentPriceServiceImpl serviceWith(Map<String, PriceResolveResult> results) {
    var sessions = mock(QuotationPriceSessions.class);
    when(sessions.open(any())).thenAnswer(call -> {
      QuotationPriceSessions.Request context = call.getArgument(0);
      capturedContext.set(context);
      return context.scope().initialize(() -> {
        var session = mock(QuotationPriceSession.class);
        when(session.conditions(any())).thenReturn(NonCuConditions.empty());
        when(session.read(any())).thenAnswer(read -> {
          java.util.Collection<QuotationPriceSession.Input> inputs = read.getArgument(0);
          var values = new java.util.LinkedHashMap<QuotationPriceSession.Input, QuotationPriceSession.Value>();
          var problems = new java.util.LinkedHashMap<QuotationPriceSession.Input, MonthlyMaterialPrices.Problem>();
          for (var input : inputs) {
            var result = results.getOrDefault(input.materialCode(), PriceResolveResult.miss("没有可用价格来源"));
            if (result.unitPrice() == null) {
              problems.put(input, new MonthlyMaterialPrices.Problem(result.failureCode() == null
                  ? MonthlyAdoptionRetryPolicy.Failure.MISSING : MonthlyAdoptionRetryPolicy.Failure.WAITING, result.remark()));
            } else {
              var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(java.time.YearMonth.parse(context.month()),
                  context.businessUnit(), input.materialCode(), input.specification(), input.unit(), "CNY", TaxBasis.EXCLUDED, null),
                  input.organization(), input.conditions(), input.basisQuantity());
              var selection = new MonthlyAdoptionPlan.Selection(1L,
                  new MonthlyPriceCoverage(java.util.Set.of(input.organization()), QuantityInterval.all(), ConditionDomain.any()),
                  MonthlyAdoptionPlan.PriceFact.normal(result.unitPrice(),
                      MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"fixture\":\"captured monthly input\"}"), java.util.Set.of()));
              values.put(input, new QuotationPriceSession.Value(request, selection, result.unitPrice(), result.unitPrice()));
            }
          }
          return new QuotationPriceSession.Read(values, problems);
        });
        return session;
      });
    });
    var materials = mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class);
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenAnswer(call -> {
      java.util.Collection<String> codes = call.getArgument(0);
      return codes.stream().map(code -> {
        var master = new com.sanhua.marketingcost.entity.MaterialMasterRaw();
        master.setMaterialCode(code); master.setUnit("件"); master.setMaterialSpec("SPEC-" + code); return master;
      }).toList();
    });
    return new PackageComponentPriceServiceImpl(snapshotService, priceMapper, priceDetailMapper, gapItemMapper,
        materials, sessions, new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
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

}
