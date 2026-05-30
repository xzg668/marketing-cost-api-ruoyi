package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PackageComponentPricePrepareStrategyImplTest {

  private PackageComponentPriceService packageComponentPriceService;
  private PackageComponentPricePrepareStrategyImpl strategy;

  @BeforeEach
  void setUp() {
    packageComponentPriceService = mock(PackageComponentPriceService.class);
    strategy = new PackageComponentPricePrepareStrategyImpl(packageComponentPriceService);
  }

  @Test
  @DisplayName("包装组件：自动从 BOM 计划行传 topProductCode + packageMaterialCode")
  void passesTopProductAndPackageCode() {
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class)))
        .thenReturn(priceResult("PRICED", true, new BigDecimal("4.50"), 700L, List.of()));

    PackageComponentPricePrepareResult result =
        strategy.prepare("PPR-001", "OA-001", "2026-05", "主制造", "U9", planItem());

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("4.50");
    assertThat(result.getAmount()).isEqualByComparingTo("11.250");
    assertThat(result.getResultRefType()).isEqualTo("PACKAGE_PRICE");
    assertThat(result.getResultRefId()).isEqualTo(700L);
    ArgumentCaptor<PackagePriceRequest> captor = ArgumentCaptor.forClass(PackagePriceRequest.class);
    verify(packageComponentPriceService).ensurePrice(captor.capture());
    assertThat(captor.getValue().getTopProductCode()).isEqualTo("TOP-001");
    assertThat(captor.getValue().getPackageMaterialCode()).isEqualTo("PKG-001");
    assertThat(captor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(captor.getValue().getBomPurpose()).isEqualTo("主制造");
    assertThat(captor.getValue().getSourceType()).isEqualTo("U9");
    assertThat(captor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(captor.getValue().getCalcBatchId()).isEqualTo("PPR-001");
    assertThat(captor.getValue().getAsOfDate()).isEqualTo(LocalDate.now());
    assertThat(captor.getValue().isForceRefresh()).isTrue();
  }

  @Test
  @DisplayName("T22：月度准备传递 price_as_of_time 并派生 asOfDate")
  void monthlyPreparePassesPriceAsOfTime() {
    LocalDateTime priceAsOfTime = LocalDateTime.of(2026, 5, 26, 10, 30);
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class)))
        .thenReturn(priceResult("PRICED", true, new BigDecimal("4.50"), 703L, List.of()));

    PackageComponentPricePrepareResult result =
        strategy.prepare("MR-001", "OA-001", "2026-05", priceAsOfTime, "主制造", "U9", planItem());

    assertThat(result.getStatus()).isEqualTo("READY");
    ArgumentCaptor<PackagePriceRequest> captor = ArgumentCaptor.forClass(PackagePriceRequest.class);
    verify(packageComponentPriceService).ensurePrice(captor.capture());
    assertThat(captor.getValue().getPriceAsOfTime()).isEqualTo(priceAsOfTime);
    assertThat(captor.getValue().getAsOfDate()).isEqualTo(LocalDate.parse("2026-05-26"));
  }

  @Test
  @DisplayName("包装组件：服务返回缺结构时转 MISSING_STRUCTURE")
  void missingStructure() {
    PackagePriceResult priceResult = priceResult("MISSING_STRUCTURE", false, null, 701L, List.of());
    priceResult.getWarnings().add("包装组件缺结构，无法生成子件价格");
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class))).thenReturn(priceResult);

    PackageComponentPricePrepareResult result =
        strategy.prepare("PPR-001", "OA-001", "2026-05", "主制造", "U9", planItem());

    assertThat(result.getStatus()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().get(0).getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getGaps().get(0).getGapMaterialCode()).isEqualTo("PKG-001");
    assertThat(result.getGaps().get(0).getSourceTable()).isEqualTo("PackageComponentSnapshotService");
  }

  @Test
  @DisplayName("包装组件：服务返回缺子件价时转 MISSING_PRICE，缺口指向子件")
  void missingChildPrice() {
    PackageComponentPriceDetail missingDetail = new PackageComponentPriceDetail();
    missingDetail.setChildMaterialCode("CHILD-001");
    missingDetail.setPriceStatus("MISSING_PRICE");
    missingDetail.setMissingReason("固定价无记录");
    when(packageComponentPriceService.ensurePrice(any(PackagePriceRequest.class)))
        .thenReturn(priceResult("MISSING_CHILD_PRICE", false, null, 702L, List.of(missingDetail)));

    PackageComponentPricePrepareResult result =
        strategy.prepare("PPR-001", "OA-001", "2026-05", "主制造", "U9", planItem());

    assertThat(result.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().get(0).getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(result.getGaps().get(0).getGapMaterialCode()).isEqualTo("CHILD-001");
    assertThat(result.getGaps().get(0).getMessage()).contains("固定价无记录");
  }

  @Test
  @DisplayName("包装组件：缺顶层产品上下文时不调用包装价格服务")
  void missingTopProductContext() {
    PricePreparePlanItem item = planItem();
    item.setTopProductCode(null);

    PackageComponentPricePrepareResult result =
        strategy.prepare("PPR-001", "OA-001", "2026-05", "主制造", "U9", item);

    assertThat(result.getStatus()).isEqualTo("MISSING_STRUCTURE");
    assertThat(result.getGaps().get(0).getSourceTable()).isEqualTo("lp_bom_costing_row");
    verifyNoInteractions(packageComponentPriceService);
  }

  private PricePreparePlanItem planItem() {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setTopProductCode("TOP-001");
    row.setMaterialCode("PKG-001");
    row.setMaterialName("包装组件");
    row.setQtyPerTop(new BigDecimal("2.5"));
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setTopProductCode(row.getTopProductCode());
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setItemType("PACKAGE_COMPONENT");
    item.setStatus("READY");
    return item;
  }

  private PackagePriceResult priceResult(
      String status,
      boolean complete,
      BigDecimal totalPrice,
      Long priceId,
      List<PackageComponentPriceDetail> details) {
    PackageComponentPrice price = new PackageComponentPrice();
    price.setId(priceId);
    price.setPackageMaterialCode("PKG-001");
    price.setSourceTopProductCode("TOP-001");
    price.setPriceStatus(status);
    price.setPriceComplete(complete);
    price.setTotalPrice(totalPrice);
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setStatus(status);
    PackagePriceResult result = PackagePriceResult.of(
        price, details, PackageSnapshotResult.of(snapshot, List.of(), false));
    result.setStatus(status);
    result.setComplete(complete);
    return result;
  }
}
