package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScrapPriceConsumerTest extends ScrapPriceTestFixture {
  @Test
  void manufacturingScrapUsesSharedPriceAndUnknownScrapCannotUseSrm() {
    load(List.of(row(null, null, "95", null)));
    var router = mock(MaterialPriceRouterService.class);
    var linked = mock(LinkedPriceEnsureService.class);
    var resolver = new MakePartMaterialPriceResolveServiceImpl(router, List.of(), linked, service);
    var result =
        resolver.calculateMaterialUnitPrice(
            "scrap",
            "2026-10",
            LocalDate.of(2026, 10, 19),
            LocalDateTime.of(2026, 10, 19, 10, 0),
            "OA",
            "COMMERCIAL",
            finance(),
            MakePartMaterialPriceResolveService.Purpose.SCRAP);
    assertThat(result.getStatus()).isEqualTo("OK");
    assertThat(result.getUnitPrice()).isEqualByComparingTo(expected("95"));
    assertThat(evidence.read(result.getEvidence().inputSnapshotJson()).consumerOrgCode())
        .isEqualTo("220");
    var missing =
        resolver.calculateMaterialUnitPrice(
            "unregistered",
            "2026-10",
            LocalDate.of(2026, 10, 19),
            LocalDateTime.of(2026, 10, 19, 10, 0),
            "OA",
            "COMMERCIAL",
            finance(),
            MakePartMaterialPriceResolveService.Purpose.SCRAP);
    assertThat(missing.getStatus()).isEqualTo("MISSING_PRICE");
    assertThat(missing.getRemark()).contains("没有适用价格规则");
    verifyNoInteractions(router, linked);
  }

  @Test
  void registeredScrapInterceptsOrdinaryRouteAndByproductKeepsNegativeQuantity() {
    load(List.of(row(null, null, null, "10.177")));
    var router = mock(MaterialPriceRouterService.class);
    var linked = mock(LinkedPriceEnsureService.class);
    var strategy = new NormalMaterialPricePrepareStrategyImpl(router, linked, List.of(), service, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader.class));
    var bom = new BomCostingRow();
    bom.setSettlementRowType("BYPRODUCT_EXTRA");
    bom.setShapeAttr("副产品");
    bom.setPriceOrgCode("220");
    var item = new PricePreparePlanItem();
    item.setBomRow(bom);
    item.setMaterialCode("scrap");
    bom.setQtyPerTop(new BigDecimal("-0.25"));
    var result =
        strategy.calculate(
            "OA", "COMMERCIAL", "2026-10", LocalDateTime.of(2026, 10, 19, 10, 0), finance(), item);
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getResultRefType()).isEqualTo("SCRAP_PRICE");
    assertThat(result.getAmount()).isEqualByComparingTo("-2.54425");
    assertThat(evidence.read(result.getInputSnapshotJson()).unitPrice())
        .isEqualByComparingTo("10.177");
    bom.setSettlementRowType("NORMAL");
    bom.setShapeAttr("原材料");
    var ordinaryShape =
        strategy.calculate(
            "OA", "COMMERCIAL", "2026-10", LocalDateTime.of(2026, 10, 19, 10, 0), finance(), item);
    assertThat(ordinaryShape.getResultRefType()).isEqualTo("SCRAP_PRICE");
    verifyNoInteractions(router, linked);
  }
}
