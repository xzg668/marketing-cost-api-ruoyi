package com.sanhua.marketingcost.service.collaboration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-05 现有价格准备只读扫描适配")
class ExistingPricePrepareScanGatewayTest {

  @Test
  @DisplayName("价格齐全时返回READY且只调用calculate")
  void readyUsesCalculateOnly() {
    PricePrepareService service = mock(PricePrepareService.class);
    when(service.calculate(any())).thenReturn(calculation("SUCCESS", 8, 0, List.of()));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.READY);
    assertThat(result.checkedItemCount()).isEqualTo(8);
    verify(service).calculate(any());
    verify(service, never()).generate(any());
  }

  @Test
  @DisplayName("只把MISSING_PRICE加MAINTAIN_PRICE作为真实补价缺口")
  void mapsOnlyRealPriceGapAndKeepsOfficialType() {
    PricePrepareService service = mock(PricePrepareService.class);
    PricePrepareGap gap = gap("MISSING_PRICE", "MAINTAIN_PRICE", "RAW-1");
    gap.setPriceType("LINKED");
    when(service.calculate(any())).thenReturn(calculation("PARTIAL", 8, 1, List.of(gap)));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.GAPS);
    assertThat(result.gaps()).hasSize(1);
    assertThat(result.gaps().get(0).existingOfficialPriceType()).isEqualTo("LINKED");
  }

  @Test
  @DisplayName("新品无价格类型也是技术真实缺口，不误判成系统结构异常")
  void missingPriceTypeIsRealTechnicalGap() {
    PricePrepareService service = mock(PricePrepareService.class);
    PricePrepareGap gap = gap("MISSING_PRICE_TYPE", "NO_ROUTE", "NEW-1");
    when(service.calculate(any())).thenReturn(calculation("PARTIAL", 1, 1, List.of(gap)));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.GAPS);
    assertThat(result.gaps()).singleElement().satisfies(item -> {
      assertThat(item.materialCode()).isEqualTo("NEW-1");
      assertThat(item.gapType()).isEqualTo("MISSING_PRICE_TYPE");
    });
  }

  @Test
  @DisplayName("同一缺价料在不同BOM路径分别保留用量、月份、组织和来源")
  void enrichesEveryBomPositionWithTraceFields() {
    PricePrepareService service = mock(PricePrepareService.class);
    PricePrepareBomItemLoader loader = mock(PricePrepareBomItemLoader.class);
    PricePrepareGap gap = gap("MISSING_PRICE", "MAINTAIN_PRICE", "RAW-1");
    when(service.calculate(any())).thenReturn(calculation("PARTIAL", 2, 1, List.of(gap)));
    when(loader.loadByQuoteItem("OA-1", 2L, "P-1", "2026-08"))
        .thenReturn(List.of(
            costingRow(10L, "/P-1/M-1/RAW-1/", "0.25"),
            costingRow(12L, "/P-1/M-1/RAW-1/", "0.50"),
            costingRow(11L, "/P-1/M-2/RAW-1/", "0.75")));

    CollaborationPriceScanResult result =
        new ExistingPricePrepareScanGateway(service, loader).check(context());

    assertThat(result.gaps()).hasSize(2);
    assertThat(result.gaps()).extracting(CollaborationPriceScanResult.PriceGap::bomPath)
        .containsExactly("/P-1/M-1/RAW-1/", "/P-1/M-2/RAW-1/");
    assertThat(result.gaps()).extracting(CollaborationPriceScanResult.PriceGap::bomQuantity)
        .containsExactly(new BigDecimal("0.75"), new BigDecimal("0.75"));
    assertThat(result.gaps()).allSatisfy(item -> {
      assertThat(item.accountingMonth()).isEqualTo("2026-08");
      assertThat(item.applicableOrgCode()).isEqualTo("210");
      assertThat(item.sourceType()).isEqualTo("PRICE_PREPARE");
      assertThat(item.bomUnit()).isEqualTo("kg");
    });
  }

  @Test
  @DisplayName("缺结构或主档属于数据异常，不误生成PRICE_ONLY")
  void structureGapIsError() {
    PricePrepareService service = mock(PricePrepareService.class);
    PricePrepareGap gap = gap("MISSING_STRUCTURE", "MAINTAIN_STRUCTURE", "P-1");
    when(service.calculate(any())).thenReturn(calculation("PARTIAL", 0, 1, List.of(gap)));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.ERROR);
    assertThat(result.message()).contains("不能误生成技术补价任务");
  }

  @Test
  @DisplayName("现有价格服务异常返回结构化ERROR")
  void priceServiceError() {
    PricePrepareService service = mock(PricePrepareService.class);
    when(service.calculate(any())).thenThrow(new IllegalStateException("price backend down"));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.ERROR);
    assertThat(result.message()).contains("price backend down");
  }

  @Test
  @DisplayName("价格汇总成功但没有任何检查明细时按数据异常处理")
  void emptySuccessfulCalculationIsError() {
    PricePrepareService service = mock(PricePrepareService.class);
    when(service.calculate(any())).thenReturn(calculation("SUCCESS", 0, 0, List.of()));

    CollaborationPriceScanResult result = new ExistingPricePrepareScanGateway(service).check(context());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.ERROR);
    assertThat(result.message()).contains("没有形成任何可检查明细");
  }

  private PricePrepareCalculationResult calculation(
      String status, int total, int gapCount, List<PricePrepareGap> gaps) {
    PricePrepareGenerateResult summary = new PricePrepareGenerateResult();
    summary.setStatus(status);
    summary.setTotalCount(total);
    summary.setGapCount(gapCount);
    PricePrepareCalculationResult result = new PricePrepareCalculationResult();
    result.setSummary(summary);
    result.setGaps(gaps);
    return result;
  }

  private PricePrepareGap gap(String gapType, String actionType, String materialCode) {
    PricePrepareGap gap = new PricePrepareGap();
    gap.setGapType(gapType);
    gap.setActionType(actionType);
    gap.setGapMaterialCode(materialCode);
    gap.setMessage("缺口原因");
    gap.setSourceTable("lp_price_linked_item");
    return gap;
  }

  private BomCostingRow costingRow(Long id, String path, String quantity) {
    BomCostingRow row = new BomCostingRow();
    row.setId(id);
    row.setMaterialCode("RAW-1");
    row.setMaterialName("原材料铜管");
    row.setMaterialSpec("TP2");
    row.setPath(path);
    row.setQtyPerTop(new BigDecimal(quantity));
    row.setUnit("kg");
    return row;
  }

  private QuoteCollaborationScanContext context() {
    return new QuoteCollaborationScanContext(
        1L,
        2L,
        "OA-1",
        "2026-08",
        "COMMERCIAL",
        "P-1",
        "产品",
        "规格",
        "型号",
        "210",
        "COMMERCIAL",
        LocalDate.of(2026, 8, 13),
        LocalDateTime.of(2026, 8, 13, 10, 0));
  }
}
