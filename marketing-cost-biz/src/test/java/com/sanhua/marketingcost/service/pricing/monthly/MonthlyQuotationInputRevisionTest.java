package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MonthlyQuotationInputRevisionTest {
  final PricePrepareItemMapper prepared = mock(PricePrepareItemMapper.class);
  final QuoteEffectiveBomNodeMapper nodes = mock(QuoteEffectiveBomNodeMapper.class);
  final QuoteCostingWorkspaceService workspaces = mock(QuoteCostingWorkspaceService.class);
  final MonthlyMaterialPrices prices = mock(MonthlyMaterialPrices.class);
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  final MonthlyQuotationInputRevision service = new MonthlyQuotationInputRevision(prepared, nodes, workspaces, prices, codec, mock(com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper.class));
  final OaForm form = new OaForm();
  final OaFormItem item = new OaFormItem();
  final MonthlyPriceRequest request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
      "COMMERCIAL", "A", null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), new BigDecimal("2.5"));
  final MonthlyAdoptionPlan.Selection selection = new MonthlyAdoptionPlan.Selection(17L,
      new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()),
      MonthlyAdoptionPlan.PriceFact.normal(new BigDecimal("10"), MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"fixed\":17}"), Set.of()));
  PricePrepareItem row;
  QuoteEffectiveBomNode parent;
  QuoteEffectiveBomNode child;

  @BeforeEach void fixture() {
    form.setId(1L); form.setOaNo("OA"); form.setBusinessUnitType("COMMERCIAL");
    item.setId(2L); item.setOaFormId(1L); item.setMaterialNo("P"); item.setBusinessUnitType("COMMERCIAL");
    var workspace = new QuoteCostingWorkspace(); workspace.setCurrentBomBuildBatchId("BOM");
    when(workspaces.find(2L, "2026-10")).thenReturn(Optional.of(workspace));
    parent = node("P", "P", null, "P", BigDecimal.ONE);
    child = node("A", "P/A", "P", "A", new BigDecimal("2.5"));
    when(nodes.selectList(any())).thenAnswer(ignored -> List.of(parent, child));
    row = new PricePrepareItem(); row.setMaterialCode("A"); row.setPriceOrgCode("210"); row.setQuantity(new BigDecimal("2.5"));
    row.setResultRefType(MonthlyPreparationPrice.KIND);
    var plan = new MonthlyAdoptionPlan(Map.of(request, selection), List.of(new MonthlyAdoptionPlan.Use("P/A", request, new BigDecimal("2.5"))), Set.of());
    row.setInputSnapshotJson(codec.encode(MonthlyPreparationPrice.of(new QuotationPriceSession.Value(request, selection,
        new BigDecimal("10"), new BigDecimal("10")), "P/A", new BigDecimal("2.5")).withInputs(MonthlyPreparationInputs.capture(plan, "P/A"))));
    when(prepared.selectList(any())).thenAnswer(ignored -> List.of(row));
    when(prices.readByIds(eq("COMMERCIAL"), any())).thenReturn(Map.of(17L, selection));
  }

  @Test void savedNormalPriceObservesOnlyTheUnpricedStructureAndNeverReadsCurrentSources() {
    var revision = service.read(form, item, "2026-10");
    assertThat(revision.preparationKnown()).isTrue();
    assertThat(revision.unadoptedMaterials()).isEmpty();
    assertThat(revision.householdObservers()).containsExactly("P");
    assertThat(service.read(form, item, "2026-10")).isEqualTo(revision);
    verify(prices, never()).resolveCandidates(any(), any());
    verify(prices, never()).resolveScrapCandidates(any(), any());
  }

  @Test void quantityUnitSpecificationAndOrganizationChangesCannotReuseAnOldPriceBinding() {
    var prior = service.read(form, item, "2026-10");
    child.setQtyPerTop(new BigDecimal("7"));
    var changed = service.read(form, item, "2026-10");
    assertThat(changed.evidence()).isNotEqualTo(prior.evidence());
    assertThat(changed.unadoptedMaterials()).containsExactly("A");
    child.setQtyPerTop(new BigDecimal("2.5")); child.setMaterialUnit("KG");
    assertThat(service.read(form, item, "2026-10").unadoptedMaterials()).contains("A");
    child.setMaterialUnit("件"); child.setMaterialSpec("changed");
    assertThat(service.read(form, item, "2026-10").unadoptedMaterials()).contains("A");
    child.setMaterialSpec(null); child.setPriceOrgCode("220");
    assertThat(service.read(form, item, "2026-10").unadoptedMaterials()).contains("A");
  }

  @Test void aNewUnpricedLeafIsIncludedWithoutInvalidatingTheAdoptedSibling() {
    var added = node("B", "P/B", "P", "B", BigDecimal.ONE);
    when(nodes.selectList(any())).thenReturn(List.of(parent, child, added));
    var scope = service.read(form, item, "2026-10");
    assertThat(scope.unadoptedMaterials()).containsExactly("B");
    assertThat(scope.householdObservers()).containsExactlyInAnyOrder("B", "P");
  }

  @Test void corruptOrMissingSavedFactsFailClosed() {
    when(prices.readByIds(eq("COMMERCIAL"), any())).thenReturn(Map.of());
    assertThatThrownBy(() -> service.read(form, item, "2026-10"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("保存月价依据不一致");
  }

  @Test void aPendingSiblingOnlyObservesItsOwnSourceAndNecessaryAggregateDependencies() {
    var pending = new PricePrepareItem(); pending.setMaterialCode("MAKE"); pending.setQuantity(BigDecimal.ONE);
    pending.setResultRefType("MAKE_PART_PRICE");
    pending.setInputSnapshotJson("{\"calculatorVersion\":\"MAKE_PART_V1\",\"components\":[{\"materialCode\":\"RAW\"}],"
        + "\"rows\":[{\"childMaterialNo\":\"RAW\",\"scrapCode\":\"SCRAP\"}]}");
    when(prepared.selectList(any())).thenReturn(List.of(row, pending));
    var scope = service.read(form, item, "2026-10");
    assertThat(scope.preparationKnown()).isTrue();
    assertThat(scope.unadoptedMaterials()).containsExactlyInAnyOrder("MAKE", "RAW", "SCRAP");
    assertThat(scope.householdObservers()).containsExactlyInAnyOrder("P", "MAKE", "RAW", "SCRAP");
    assertThat(scope.unadoptedMaterials()).doesNotContain("A");
    verify(prices).readByIds("COMMERCIAL", new TreeSet<>(Set.of(17L)));
  }

  @Test void aggregateEvidenceIncludesEveryBaseDependencyWithoutCreatingAParentPrice() throws Exception {
    var dependency = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10),
        "COMMERCIAL", "SCRAP", null, "KG", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
    var scrap = new MonthlyAdoptionPlan.Selection(18L,
        new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()),
        MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.ONE, MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"scrap\":18}"), Set.of()));
    var dependent = new MonthlyAdoptionPlan.Selection(17L, selection.applicability(),
        MonthlyAdoptionPlan.PriceFact.normal(BigDecimal.TEN, selection.fact().evidence(), Set.of(dependency)));
    var input = MonthlyPreparationInputs.capture(new MonthlyAdoptionPlan(Map.of(request, dependent, dependency, scrap),
        List.of(new MonthlyAdoptionPlan.Use("P/A", request, new BigDecimal("2.5"))), Set.of()), "P");
    row.setMaterialCode("P"); row.setQuantity(BigDecimal.ONE); row.setResultRefType("MAKE_PART");
    row.setInputSnapshotJson(new ObjectMapper().writeValueAsString(Map.of("calculatorVersion", "MAKE_PART_V1", "monthlyInputs", codec.encode(input))));
    when(prices.readByIds(eq("COMMERCIAL"), any())).thenReturn(Map.of(17L, dependent, 18L, scrap));
    var scope = service.read(form, item, "2026-10");
    assertThat(scope.unadoptedMaterials()).isEmpty();
    assertThat(scope.householdObservers()).containsExactly("P");
    verify(prices).readByIds("COMMERCIAL", new TreeSet<>(Set.of(17L, 18L)));
    when(prices.readByIds(eq("COMMERCIAL"), any())).thenReturn(Map.of(17L, dependent));
    assertThatThrownBy(() -> service.read(form, item, "2026-10")).hasMessageContaining("保存月价依据不一致");
  }

  @Test void householdSuppressionExcludesItsChildrenFromEveryMutableSourceScope() {
    var household = new MonthlyAdoptionPlan.Selection(17L, selection.applicability(),
        MonthlyAdoptionPlan.PriceFact.household(BigDecimal.TEN, new BigDecimal("0.085"), selection.fact().evidence()));
    var lower = node("MISSING", "P/A/MISSING", "A", "MISSING", BigDecimal.ONE);
    when(nodes.selectList(any())).thenReturn(List.of(parent, child, lower));
    var plan = new MonthlyAdoptionPlan(Map.of(request, household),
        List.of(new MonthlyAdoptionPlan.Use("P/A", request, new BigDecimal("2.5"))), Set.of("P/A/MISSING"));
    row.setInputSnapshotJson(codec.encode(MonthlyPreparationPrice.of(new QuotationPriceSession.Value(request, household,
        BigDecimal.TEN, BigDecimal.TEN), "P/A", new BigDecimal("2.5")).withInputs(MonthlyPreparationInputs.capture(plan, "P/A"))));
    when(prices.readByIds(eq("COMMERCIAL"), any())).thenReturn(Map.of(17L, household));
    var scope = service.read(form, item, "2026-10");
    assertThat(scope.unadoptedMaterials()).isEmpty();
    assertThat(scope.householdObservers()).containsExactly("P");
    lower.setQtyPerTop(BigDecimal.TEN);
    assertThat(service.read(form, item, "2026-10")).isEqualTo(scope);
  }

  private static QuoteEffectiveBomNode node(String key, String path, String parent, String code, BigDecimal q) {
    var node = new QuoteEffectiveBomNode(); node.setNodeKey(key); node.setNodePath(path); node.setParentNodeKey(parent);
    node.setMaterialCode(code); node.setMaterialUnit("件"); node.setPriceOrgCode("210"); node.setQtyPerTop(q);
    return node;
  }
}
