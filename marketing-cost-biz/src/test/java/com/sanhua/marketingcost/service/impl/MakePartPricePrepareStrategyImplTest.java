package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MakePartPricePrepareStrategyImplTest {
  final MakePartPriceGenerationService generation = mock(MakePartPriceGenerationService.class);
  final MakePartPricePrepareStrategyImpl service = new MakePartPricePrepareStrategyImpl(generation);

  @Test void usesExactNodeResultAndMultipliesQuantityOnce() {
    var item = item(); var ready = row("OK", "RAW", "SCRAP");
    ready.setId(55L); ready.setPriceComplete(true); ready.setParentTotalCostPrice(new BigDecimal(".965"));
    when(generation.calculateForBomRow(item.getBomRow(), null, null, true)).thenReturn(List.of(ready));
    var result = service.prepare("OA", "COMMERCIAL", "2026-09", item);
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo(".965");
    assertThat(result.getAmount()).isEqualByComparingTo("1.930");
    verify(generation).calculateForBomRow(item.getBomRow(), null, null, true);
  }

  @Test void readonlyAndFinanceScenarioReachSameNodeWithExactContext() {
    var item = item(); var ready = row("OK", "RAW", "SCRAP");
    ready.setPriceComplete(true); ready.setParentTotalCostPrice(BigDecimal.ONE);
    var time = LocalDateTime.of(2026,9,17,10,0);
    var context = new PricePrepareScenarioContext(QuotePriceScenarioType.FINANCE_QUOTE_BASE, "GROUP", "PPR", Map.of("Cu",new BigDecimal("90")));
    when(generation.calculateForBomRow(item.getBomRow(), time, context, false)).thenReturn(List.of(ready));
    assertThat(service.calculate("OA", "COMMERCIAL", "2026-09", time, context, item).getStatus()).isEqualTo("READY");
    verify(generation).calculateForBomRow(item.getBomRow(), time, context, false);
  }

  @ParameterizedTest
  @CsvSource({"MISSING_RAW_PRICE,MISSING_PRICE,RAW", "MISSING_SCRAP_PRICE,MISSING_PRICE,SCRAP", "MISSING_WEIGHT,MISSING_STRUCTURE,RAW", "MISSING_SCRAP_MAPPING,MISSING_STRUCTURE,RAW", "MISSING_BOM,MISSING_STRUCTURE,MAKE"})
  void identifiesActualMissingMaterial(String upstream, String status, String code) {
    var item = item();
    when(generation.calculateForBomRow(any(), any(), any(), eq(true))).thenReturn(List.of(row(upstream,"RAW","SCRAP")));
    var result = service.prepare("OA", "COMMERCIAL", "2026-09", item);
    assertThat(result.getStatus()).isEqualTo(status);
    assertThat(result.getGaps()).hasSize(1);
    assertThat(result.getGaps().getFirst().getGapMaterialCode()).isEqualTo(code);
  }

  @Test void partialChildSuccessCannotHideOtherChildFailure() {
    var good = row("OK", "RAW", "SCRAP"); good.setPriceComplete(true); good.setParentTotalCostPrice(BigDecimal.ONE);
    when(generation.calculateForBomRow(any(), any(), any(), anyBoolean()))
        .thenReturn(List.of(good,row("MISSING_RAW_PRICE","RAW2","SCRAP")));
    assertThat(service.prepare("OA","COMMERCIAL","2026-09",item()).getStatus()).isEqualTo("MISSING_PRICE");
  }

  @Test void rejectsDifferentQuoteMonthBeforeCalculation() {
    var item = item(); item.getBomRow().setPeriodMonth("2026-10");
    assertThatThrownBy(() -> service.prepare("OA","COMMERCIAL","2026-09",item)).hasMessageContaining("不一致");
    verifyNoInteractions(generation);
  }

  private PricePreparePlanItem item() {
    var row = new BomCostingRow(); row.setId(1L); row.setOaFormItemId(2L); row.setOaNo("OA");
    row.setPeriodMonth("2026-09"); row.setBusinessUnitType("COMMERCIAL"); row.setMaterialCode("MAKE"); row.setQtyPerTop(new BigDecimal("2"));
    var item = new PricePreparePlanItem(); item.setBomRow(row); item.setBomRowId(1L); item.setMaterialCode("MAKE"); return item;
  }

  private MakePartPriceCalcRow row(String status,String raw,String scrap) {
    var row = new MakePartPriceCalcRow(); row.setStatus(status); row.setParentMaterialNo("MAKE"); row.setChildMaterialNo(raw); row.setScrapCode(scrap); return row;
  }
}
