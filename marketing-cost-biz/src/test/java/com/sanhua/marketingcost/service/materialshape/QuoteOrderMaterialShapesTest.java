package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestItemResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteMaterialShapeScope;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.Entry;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuoteOrderMaterialShapesTest {
  private final QuoteRequestQueryService requests = mock(QuoteRequestQueryService.class);
  private final QuoteEffectiveBomApplicationService boms = mock(QuoteEffectiveBomApplicationService.class);
  private final MaterialPriceSourceService sources = mock(MaterialPriceSourceService.class);
  private final QuoteMaterialShapeInputs inputs = mock(QuoteMaterialShapeInputs.class);
  private final QuoteProductBomPreparationService preparations = mock(QuoteProductBomPreparationService.class);
  private final QuoteOrderMaterialShapes service = new QuoteOrderMaterialShapes(requests, boms, sources, inputs,
      mock(QuoteCostingWorkspaceService.class), mock(QuoteCostRunVersionInvalidationService.class),
      mock(JdbcTemplate.class), preparations);
  private final QuoteMaterialShapeScope scope = new QuoteMaterialShapeScope("2026-11", "PRODUCT", "BOM-V1",
      List.of(new QuoteMaterialShapeScope.Material("210", "A", "共享自制件", "SPEC", "制造件")));

  @BeforeEach
  void givenExistingOrderAndMultipleSources() {
    var item = new QuoteRequestItemResponse();
    item.setId(10L); item.setMaterialNo("PRODUCT"); item.setProductName("产品");
    var detail = new QuoteRequestDetailResponse();
    detail.setId(1L); detail.setAccountingPeriodMonth("2026-11"); detail.setItems(List.of(item));
    when(requests.getRequestDetail("OA")).thenReturn(detail);
    when(inputs.orderInstalled()).thenReturn(true);
    when(boms.materialShapeScope("OA", 10L)).thenReturn(scope);
    when(sources.quotationSources(any(), eq("2026-11"), eq("210"), eq("A"))).thenReturn(List.of(
        new Entry(1, "A", "共享自制件", null, "SPEC", "210", null, "MAKE", "自制件算价", List.of(), "MANUFACTURE", true, 0),
        new Entry(2, "A", "共享自制件", null, "SPEC", "210", null, "LINKED", "联动价", List.of(), "PURCHASE", false, 0)));
  }

  @Test
  void openingPreparedOrderDiscoversSourcesWithoutRepeatingProductBomPreparation() {
    var view = service.prepare("OA", "finance");
    assertThat(view.problems()).isEmpty();
    assertThat(view.rows()).hasSize(1);
    assertThat(view.rows().getFirst().sources()).extracting(Entry::sourceType).containsExactly("MAKE", "LINKED");
    verifyNoInteractions(preparations);
    verify(sources).discoverForQuotation(any(), eq("2026-11"), eq("210"), eq(List.of("A")), eq(java.util.Set.of("A")), eq("finance"));
  }

  @Test
  void missingPreparationStillUsesOriginalPreparationBeforeDiscoveringSources() {
    when(boms.materialShapeScope("OA", 10L))
        .thenThrow(new QuoteEffectiveBomQueryException("EFFECTIVE_BOM_NOT_FOUND", "尚未准备"))
        .thenReturn(scope);
    var view = service.prepare("OA", "finance");
    assertThat(view.problems()).isEmpty();
    assertThat(view.rows()).hasSize(1);
    verify(preparations).prepareByOaFormItem(eq(10L), any(), eq("2026-11"));
    verify(boms, times(2)).materialShapeScope("OA", 10L);
  }

  @Test
  void missingMonthlyBomStillUsesOriginalPreparationAndReportsRemainingGap() {
    when(boms.materialShapeScope("OA", 10L))
        .thenThrow(new QuoteEffectiveBomQueryException("MONTHLY_BOM_NOT_READY", "本产品月度BOM尚未准备"));
    var view = service.prepare("OA", "finance");
    assertThat(view.rows()).isEmpty();
    assertThat(view.problems()).extracting(QuoteOrderMaterialShapes.Problem::message)
        .containsExactly("本产品月度BOM尚未准备");
    verify(preparations).prepareByOaFormItem(eq(10L), any(), eq("2026-11"));
  }

  @Test
  void blockedAlternativeOrScopeIsReportedWithoutRebuildingItsBom() {
    when(boms.materialShapeScope("OA", 10L))
        .thenThrow(new QuoteEffectiveBomQueryException("BOM_SCOPE_BLOCKED", "请先选择替代件"));
    var view = service.prepare("OA", "finance");
    assertThat(view.rows()).isEmpty();
    assertThat(view.problems()).extracting(QuoteOrderMaterialShapes.Problem::message)
        .containsExactly("请先选择替代件");
    verifyNoInteractions(preparations, sources);
  }

  @Test
  void readingExistingChoicesDoesNotPrepareOrDiscover() {
    assertThat(service.list("OA").rows()).hasSize(1);
    verifyNoInteractions(preparations);
    verify(sources, never()).discoverForQuotation(any(), any(), any(), any(), any());
    verify(sources, never()).discoverForQuotation(any(), any(), any(), anyCollection(), anySet(), any());
    verify(sources, never()).includeCalculatedMake(any(), any(), any(), any());
  }

  @Test
  void nativePurchasedMaterialIsHiddenEvenWhenItHasMultiplePriceSources() {
    when(boms.materialShapeScope("OA", 10L)).thenReturn(withShape("采购件"));
    assertThat(service.prepare("OA", "finance").rows()).isEmpty();
    assertThat(service.list("OA").rows()).isEmpty();
  }

  @Test
  void mixedPurchasedAndManufacturedConsumersCanOnlyUnifyAsPurchase() {
    givenSecondConsumer("采购件");
    var row = service.list("OA").rows().getFirst();
    assertThat(row.products()).hasSize(2);
    assertThat(row.allowedShapes()).containsExactly("PURCHASE");
  }

  @Test
  void mixedPurchasedAndOutsourcedConsumersCanOnlyUnifyAsPurchase() {
    when(boms.materialShapeScope("OA", 10L)).thenReturn(withShape("委外加工件"));
    givenSecondConsumer("采购件");
    assertThat(service.list("OA").rows().getFirst().allowedShapes()).containsExactly("PURCHASE");
  }

  @Test
  void nativeOutsourceStillAllowsPurchaseAndOutsource() {
    when(boms.materialShapeScope("OA", 10L)).thenReturn(withShape("委外加工件"));
    assertThat(service.list("OA").rows().getFirst().allowedShapes()).containsExactly("PURCHASE", "OUTSOURCE");
  }

  @Test
  void storedOutsourceOrManufactureChoiceCannotConvertAnOriginallyPurchasedBom() {
    var target = spy(new QuoteMaterialShapeInputs(mock(JdbcTemplate.class)));
    for (String selected : List.of("OUTSOURCE", "MANUFACTURE")) {
      var catalog = new QuoteMaterialShapeInputs.Catalog(List.of(
          new QuoteMaterialShapeInputs.Source("210", "A", "FIXED", "PURCHASE", 0),
          new QuoteMaterialShapeInputs.Source("210", "A", "LINKED", "OUTSOURCE", 0)),
          List.of(new QuoteMaterialShapeInputs.Choice("210", "A", selected, 1)));
      doReturn(catalog).when(target).read(eq("COMMERCIAL"), eq("2026-11"), eq(10L), anyCollection());
      var original = com.sanhua.marketingcost.service.effectivebom.EffectiveBomShapeDecision.u9(
          "A", "采购件", com.sanhua.marketingcost.enums.QuoteMaterialShape.PURCHASE);
      var changed = target.apply("COMMERCIAL", "2026-11", 10L, java.util.Map.of("A", original), java.util.Map.of("A", "210")).get("A");
      assertThat(changed.blocked()).as(selected).isTrue();
      assertThat(changed.blockingReason()).contains("采购件");
    }
  }

  @Test
  void choosingTheSameSourceAgainKeepsItsAdoptedMonthlyPriceScope() {
    var sources = List.of(new QuoteMaterialShapeInputs.Source("210", "A", "FIXED", null, 0),
        new QuoteMaterialShapeInputs.Source("210", "A", "LINKED", null, 0));
    var first = new QuoteMaterialShapeInputs.Catalog(sources,
        List.of(new QuoteMaterialShapeInputs.Choice("210", "A", "PURCHASE", 1, "FIXED")));
    var returned = new QuoteMaterialShapeInputs.Catalog(sources,
        List.of(new QuoteMaterialShapeInputs.Choice("210", "A", "PURCHASE", 3, "FIXED")));
    assertThat(first.priceScope("210", "A", "PURCHASE")).isEqualTo(returned.priceScope("210", "A", "PURCHASE"));
    assertThat(first.fingerprint()).isNotEqualTo(returned.fingerprint());
  }

  private QuoteMaterialShapeScope withShape(String shape) {
    return new QuoteMaterialShapeScope("2026-11", "PRODUCT", "BOM-V1",
        List.of(new QuoteMaterialShapeScope.Material("210", "A", "共享物料", "SPEC", shape)));
  }

  private void givenSecondConsumer(String shape) {
    var detail = requests.getRequestDetail("OA");
    var item = new QuoteRequestItemResponse(); item.setId(11L); item.setMaterialNo("PRODUCT-2"); item.setProductName("产品2");
    detail.setItems(List.of(detail.getItems().getFirst(), item));
    when(boms.materialShapeScope("OA", 11L)).thenReturn(withShape(shape));
  }
}
