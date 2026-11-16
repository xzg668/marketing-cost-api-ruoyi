package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuoteFinanceSourceBoundaryTest {
  private final MonthlyMaterialPrices.Context context = new MonthlyMaterialPrices.Context(
      "COMMERCIAL", "FI-SC-006-20261111-001", 174L, "1001900001293",
      YearMonth.of(2026,11), LocalDateTime.of(2026,11,13,0,0), "test", "210");
  private MonthlyQuotationPrices.Item item(String code) {
    return new MonthlyQuotationPrices.Item(code,"210","千克",null,TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),new BigDecimal("0.00047"),MonthlyQuotationPrices.Purpose.MATERIAL);
  }
  private QuoteMaterialShapeInputs target(List<QuoteMaterialShapeInputs.Choice> choices) {
    var jdbc=mock(JdbcTemplate.class);
    // The original code looked at effective PURCHASE and imposed an empty classification scope.
    when(jdbc.queryForList(anyString(),eq(174L),eq("2026-11"),eq("COMMERCIAL"))).thenReturn(List.of(
        Map.of("price_org_code","210","material_code","301040002","effective_material_shape","PURCHASE"),
        Map.of("price_org_code","210","material_code","301040638","effective_material_shape","PURCHASE")));
    var target=spy(new QuoteMaterialShapeInputs(jdbc));
    var sources=new ArrayList<QuoteMaterialShapeInputs.Source>();
    for(String code:List.of("301040002","301040638","PARENT")) {
      sources.add(new QuoteMaterialShapeInputs.Source("210",code,"LINKED",null,0));
      sources.add(new QuoteMaterialShapeInputs.Source("210",code,"ORDER",null,0));
    }
    doReturn(new QuoteMaterialShapeInputs.Catalog(sources,choices)).when(target)
        .read(eq("COMMERCIAL"),eq("2026-11"),eq(174L),anyCollection());
    return target;
  }
  @Test void ordinaryPurchasedWiresWithMultipleUnclassifiedSourcesKeepOriginalPriceAndMonthlyScope() {
    var wires=List.of(item("301040002"),item("301040638"));
    assertThat(target(List.of()).priceScopes(context,wires)).isEmpty();
  }
  @Test void selectingParentCannotImposeItsFinanceClassificationGateOnUnchangedPurchasedChildren() {
    var choices=List.of(new QuoteMaterialShapeInputs.Choice("210","PARENT","PURCHASE",1));
    assertThat(target(choices).priceScopes(context,List.of(item("301040002"),item("301040638")))).isEmpty();
  }
  @Test void explicitRestoreToBomDefaultAlsoKeepsOriginalPriceScope() {
    var choices=List.of(new QuoteMaterialShapeInputs.Choice("210","301040002",null,2));
    assertThat(target(choices).priceScopes(context,List.of(item("301040002")))).isEmpty();
  }
  @Test void legacyOrderSourceCannotOverrideFinanceClassification() {
    var catalog=new QuoteMaterialShapeInputs.Catalog(List.of(
        new QuoteMaterialShapeInputs.Source("210","A","FIXED","PURCHASE",1),
        new QuoteMaterialShapeInputs.Source("210","A","LINKED","OUTSOURCE",1)),
        List.of(new QuoteMaterialShapeInputs.Choice("210","A","PURCHASE",1,"LINKED")));
    String scope=catalog.priceScope("210","A","PURCHASE");
    assertThat(MaterialShapePriceScope.allows(scope,"FIXED")).isTrue();
    assertThat(MaterialShapePriceScope.allows(scope,"LINKED")).isFalse();
    assertThat(MaterialShapePriceScope.pinnedSource(scope)).isNull();
  }
  @Test void actualShapeSwitchStillRequiresFinanceClassificationDespiteOldOrderSource() {
    var catalog=new QuoteMaterialShapeInputs.Catalog(List.of(
        new QuoteMaterialShapeInputs.Source("210","A","FIXED",null,0),
        new QuoteMaterialShapeInputs.Source("210","A","LINKED",null,0)),
        List.of(new QuoteMaterialShapeInputs.Choice("210","A","PURCHASE",1,"FIXED")));
    assertThat(catalog.selectionProblem("210","A","PURCHASE")).contains("财务","形态属性");
  }
}
