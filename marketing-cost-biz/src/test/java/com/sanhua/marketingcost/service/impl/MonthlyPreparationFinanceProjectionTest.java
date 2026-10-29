package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyPreparationFinanceProjectionTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(json);
  final MonthlyPreparationEvidence evidence = new MonthlyPreparationEvidence(codec);
  final MonthlyPreparationFinanceProjection projection = new MonthlyPreparationFinanceProjection(json, codec);

  @Test void ordinaryProjectionUsesOnlySavedP0AndRetainsTheCompleteAdoptedEvidence() {
    var source = direct(false); String original = source.getInputSnapshotJson();
    var finance = projection.project(source);
    assertThat(finance).isNotSameAs(source);
    assertThat(finance.getUnitPrice()).isEqualByComparingTo("10");
    assertThat(finance.getAmount()).isEqualByComparingTo("25");
    assertThat(finance.getInputSnapshotJson()).isEqualTo(original);
    assertThat(finance.getMonthlyPriceId()).isEqualTo(source.getMonthlyPriceId());
    assertThat(finance.getResultRefId()).isEqualTo(source.getResultRefId());
    assertThat(evidence.read(finance)).isEqualTo(evidence.read(source));
    assertThat(source.getAmount()).isEqualByComparingTo("30");
    assertThat(source.getUnitPrice()).isEqualByComparingTo("12");
    assertThat(source.getInputSnapshotJson()).isEqualTo(original);
  }

  @Test void householdProjectionPreservesTheIndependentPriceAndQuantityWithoutAddingFees() {
    var source = direct(true); var finance = projection.project(source);
    assertThat(finance.getUnitPrice()).isEqualByComparingTo("20.9853");
    assertThat(finance.getAmount()).isEqualByComparingTo("15.738975");
    assertThat(finance.getMonthlyPricingType()).isEqualTo("HOUSEHOLD_INQUIRY");
    assertThat(finance.getInputSnapshotJson()).isEqualTo(source.getInputSnapshotJson());
  }

  @Test void workingUnitConversionPreservesTheAdoptedSourceUnitAndExactActualQuantity() {
    var source = direct(false); var captured = codec.decode(source.getInputSnapshotJson(), MonthlyPreparationPrice.class);
    var inputs = inputs("P/NORMAL", Map.of(captured.request(), captured.selection()),
        List.of(new Use("P/NORMAL", captured.request(), d(".0025"))));
    source.setUnitPrice(d(".012")); source.setAmount(d(".03"));
    source.setInputSnapshotJson(codec.encode(captured.withUnitPriceMultiplier(d(".001")).withInputs(inputs)));
    var finance = projection.project(source);
    assertThat(finance.getUnitPrice()).isEqualByComparingTo(".01");
    assertThat(finance.getAmount()).isEqualByComparingTo(".025");
    assertThat(evidence.read(finance).uses().getFirst().cumulativeQuantity()).isEqualByComparingTo(".0025");
    assertThat(codec.decode(finance.getInputSnapshotJson(), MonthlyPreparationPrice.class).financialPrice())
        .isEqualByComparingTo("10");
  }

  @Test void makeProjectionReusesSavedWeightsRawScrapAndSharedOrganizationApplicability() throws Exception {
    var source = make(); String original = source.getInputSnapshotJson();
    var finance = projection.project(source);
    assertThat(finance.getUnitPrice()).isEqualByComparingTo("39");
    assertThat(finance.getAmount()).isEqualByComparingTo("97.5");
    var row = new MakePartCalculationEvidence(json).rows(finance).getFirst();
    assertThat(row.getGrossWeightG()).isEqualByComparingTo("400");
    assertThat(row.getNetWeightG()).isEqualByComparingTo("300");
    assertThat(row.getRawUnitPrice()).isEqualByComparingTo("100");
    assertThat(row.getScrapUnitPrice()).isEqualByComparingTo("10");
    assertThat(row.getPriceScenarioType()).isEqualTo(QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    assertThat(row.getSourceCostingRowId()).isEqualTo(9L);
    assertThat(codec.decode(row.getScrapInputSnapshotJson(), MonthlyPreparationPrice.class).request().priceOrgCode())
        .isEqualTo("210");
    assertThat(finance.getPriceOrgCode()).isEqualTo("220");
    assertThat(evidence.read(finance)).isEqualTo(evidence.read(source));
    assertThat(json.readTree(finance.getInputSnapshotJson()).path("components").get(0).path("unitPrice").decimalValue())
        .isEqualByComparingTo("100");
    assertThat(source.getInputSnapshotJson()).isEqualTo(original);
    assertThat(source.getUnitPrice()).isEqualByComparingTo("46.8");
  }

  @Test void packProjectionUsesPackageBaseOnceAndKeepsHouseholdExcluded() throws Exception {
    var source = pack(); var original = json.readTree(source.getInputSnapshotJson());
    var finance = projection.project(source); var projected = json.readTree(finance.getInputSnapshotJson());
    assertThat(finance.getUnitPrice()).isEqualByComparingTo("16.8");
    assertThat(finance.getAmount()).isEqualByComparingTo("42");
    var normal = projected.path("components").get(0);
    assertThat(normal.path("unitPrice").decimalValue()).isEqualByComparingTo("7");
    assertThat(normal.path("amount").decimalValue()).isEqualByComparingTo("84");
    assertThat(normal.path("inputs")).isEqualTo(original.path("components").get(0).path("inputs"));
    assertThat(projected.path("components").get(1)).isEqualTo(original.path("components").get(1));
    assertThat(projected.path("components").get(1).hasNonNull("amount")).isFalse();
    assertThat(projected.path("sourceRawHierarchyId")).isEqualTo(original.path("sourceRawHierarchyId"));
    assertThat(evidence.read(finance)).isEqualTo(evidence.read(source));
    assertThat(evidence.read(finance).uses()).hasSize(1);
    assertThat(new PackageCalculationEvidence(json).validatePair(source, finance, null, null)).isTrue();
    assertThat(source.getUnitPrice()).isEqualByComparingTo("19.2");
  }

  @Test void blankMakeWithConfirmedNoScrapRetainsPiecePricingAndDoesNotInventScrapUse() {
    var source = make(); var make = new MakePartCalculationEvidence(json); var row = make.rows(source).getFirst();
    row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_BLANK); row.setNoScrapConfirmed(true);
    row.setScrapCode(null); row.setScrapUnitPrice(BigDecimal.ZERO); row.setScrapInputSnapshotJson(null);
    var raw = codec.decode(row.getRawInputSnapshotJson(), MonthlyPreparationPrice.class);
    var inputs = inputs("P/MAKE", Map.of(raw.request(), raw.selection()),
        List.of(new Use("P/MAKE/RAW", raw.request(), d("2.5"))));
    source.setUnitPrice(d("120")); source.setAmount(d("300"));
    source.setInputSnapshotJson(make.withRows(source.getInputSnapshotJson(), new MakePartPriceCalculator().calculate(List.of(row))));
    source.setInputSnapshotJson(evidence.attach(source, inputs));
    var finance = projection.project(source);
    assertThat(finance.getUnitPrice()).isEqualByComparingTo("100");
    assertThat(finance.getAmount()).isEqualByComparingTo("250");
    assertThat(make.rows(finance).getFirst().getScrapUnitPrice()).isZero();
    assertThat(evidence.read(finance).uses()).hasSize(1);
  }

  @Test void rejectsInconsistentSavedReferencesP0AndActualUseBeforeProducingFinance() throws Exception {
    var wrongId = direct(false); wrongId.setMonthlyPriceId(999L);
    assertThatThrownBy(() -> projection.project(wrongId)).hasMessageContaining("引用");
    var wrongType = direct(false); wrongType.setMonthlyPricingType("HOUSEHOLD_INQUIRY");
    assertThatThrownBy(() -> projection.project(wrongType)).hasMessageContaining("引用");
    var wrongP1 = direct(false); wrongP1.setUnitPrice(d("13")); wrongP1.setAmount(d("32.5"));
    assertThatThrownBy(() -> projection.project(wrongP1)).hasMessageContaining("P1");
    var wrongP0 = direct(false);
    var captured = codec.decode(wrongP0.getInputSnapshotJson(), MonthlyPreparationPrice.class);
    wrongP0.setInputSnapshotJson(codec.encode(new MonthlyPreparationPrice(captured.snapshotSchema(), captured.sourceKind(),
        captured.request(), captured.selection(), d("11"), captured.customerPrice(), captured.unitPriceMultiplier(),
        captured.path(), captured.quantity(), captured.monthlyInputs())));
    assertThatThrownBy(() -> projection.project(wrongP0)).hasMessageContaining("P0");
    var wrongUse = pack(); var inputs = evidence.read(wrongUse); var use = inputs.uses().getFirst();
    wrongUse.setInputSnapshotJson(evidence.attach(wrongUse, new MonthlyPreparationInputs(1, inputs.rootPath(),
        inputs.prices(), List.of(new Use(use.path(), use.request(), d("3"))), inputs.suppressedPaths())));
    assertThatThrownBy(() -> projection.project(wrongUse)).hasMessageContaining("实际用量");
  }

  @Test void rejectsForgedHouseholdExclusionPathAndMissingSourceEvidence() throws Exception {
    var source = pack(); var tree = json.readTree(source.getInputSnapshotJson());
    ((ObjectNode) tree.path("components").get(1).path("householdUse")).put("path", "OTHER/HH");
    source.setInputSnapshotJson(tree.toString());
    assertThatThrownBy(() -> projection.project(source)).hasMessageContaining("排除路径");
    var missing = direct(false); missing.setInputSnapshotJson(null);
    assertThatThrownBy(() -> projection.project(missing)).hasMessageContaining("月价");
  }

  @Test void zeroUseIsCopiedWithoutInventingAPriceOrMonthlyAdoption() {
    var source = item("ZERO", "MONTHLY_PRICE", "0", "0"); source.setUnitPrice(null);
    var finance = projection.project(source);
    assertThat(finance.getUnitPrice()).isNull(); assertThat(finance.getAmount()).isZero();
    assertThat(finance.getInputSnapshotJson()).isNull();
  }

  PricePrepareItem direct(boolean household) {
    String code = household ? "HH" : "NORMAL", quantity = household ? ".75" : "2.5";
    String p0 = household ? "20.9853" : "10", p1 = household ? p0 : "12";
    var request = request(code, "210"); var selected = selected(1L, household, p0);
    var inputs = inputs("P/" + code, Map.of(request, selected), List.of(new Use("P/" + code, request, d(quantity))));
    var source = item(code, "MONTHLY_PRICE", quantity, p1);
    source.setMonthlyPriceId(1L); source.setMonthlyPricingType(selected.fact().type().name()); source.setResultRefId(1L);
    source.setInputSnapshotJson(codec.encode(MonthlyPreparationPrice.of(
        new QuotationPriceSession.Value(request, selected, d(p0), d(p1)), "P/" + code, d(quantity)).withInputs(inputs)));
    return source;
  }

  PricePrepareItem make() {
    var raw = request("RAW", "220"); var scrap = request("SCRAP", "210");
    var rawPrice = selected(2L, false, "100"); var scrapPrice = selected(3L, false, "10");
    var inputs = inputs("P/MAKE", Map.of(raw, rawPrice, scrap, scrapPrice),
        List.of(new Use("P/MAKE/RAW", raw, d("1")), new Use("P/MAKE/SCRAP", scrap, d(".25"))));
    var row = new MakePartPriceCalcRow(); row.setId(50L); row.setCalcBatchId("MPP12-MAKE-60"); row.setSourceCostingRowId(9L);
    row.setParentMaterialNo("MAKE"); row.setBusinessUnitType("COMMERCIAL"); row.setPricingMonth("2026-10");
    row.setChildMaterialNo("RAW"); row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    row.setGrossWeightG(d("400")); row.setNetWeightG(d("300")); row.setRawUnitPrice(d("120"));
    row.setScrapCode("SCRAP"); row.setScrapUnitPrice(d("12")); row.setNoScrapConfirmed(false);
    row.setRawInputSnapshotJson(input(raw, rawPrice, "100", "120"));
    row.setScrapInputSnapshotJson(input(scrap, scrapPrice, "10", "12"));
    var parent = new BomCostingRow(); parent.setId(9L); parent.setMaterialCode("MAKE"); parent.setPriceOrgCode("220");
    parent.setBusinessUnitType("COMMERCIAL"); parent.setPeriodMonth("2026-10");
    var source = item("MAKE", "MAKE_PART_PRICE", "2.5", "46.8"); source.setPriceOrgCode("220");
    source.setInputSnapshotJson(new MakePartCalculationEvidence(json).capture(parent, new MakePartPriceCalculator().calculate(List.of(row))));
    source.setInputSnapshotJson(evidence.attach(source, inputs)); return source;
  }

  PricePrepareItem pack() {
    var raw = request("RAW", "210"); var selected = selected(4L, false, "7");
    var inputs = inputs("P/PACK", Map.of(raw, selected), List.of(new Use("P/PACK/RAW", raw, d("6"))));
    var parent = new PackageComponentPrice(); parent.setPackageMaterialCode("PACK"); parent.setBusinessUnitType("COMMERCIAL");
    parent.setPriceOrgCode("210"); parent.setPeriodMonth("2026-10"); parent.setPackageParentBaseQty(d("5"));
    parent.setSourceRawHierarchyId(100L); parent.setSourcePath("/P/PACK/");
    var ordinary = new PackageComponentPriceDetail(); ordinary.setSourceHierarchyId(101L); ordinary.setSourcePath("/P/PACK/RAW/");
    ordinary.setChildMaterialCode("RAW"); ordinary.setPriceSource("MONTHLY_PRICE"); ordinary.setPriceType("NORMAL");
    ordinary.setQtyPerParent(d("12")); ordinary.setChildParentBaseQty(d("3")); ordinary.setChildUnitPrice(d("8"));
    ordinary.setChildAmount(d("96")); ordinary.setInputSnapshotJson(input(raw, selected, "7", "8"));
    var household = new PackageComponentPriceDetail(); household.setSourceHierarchyId(102L); household.setSourcePath("/P/PACK/HH/");
    household.setChildMaterialCode("HH"); household.setPriceSource(PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED);
    household.setQtyPerParent(d("3")); household.setChildParentBaseQty(d("2"));
    household.setHouseholdUse(new Use("P/PACK/HH", request("HH", "210"), d(".75")));
    var source = item("PACK", "PACKAGE_PRICE", "2.5", "19.2");
    source.setInputSnapshotJson(new PackageCalculationEvidence(json).capture(parent, List.of(ordinary, household)));
    source.setInputSnapshotJson(evidence.attach(source, inputs)); return source;
  }

  PricePrepareItem item(String code, String type, String quantity, String price) {
    var item = new PricePrepareItem(); item.setId(900L); item.setPrepareNo("OA_PREPARE"); item.setMaterialCode(code);
    item.setBusinessUnitType("COMMERCIAL"); item.setPriceOrgCode("210"); item.setPeriodMonth("2026-10");
    item.setStatus("READY"); item.setResultRefType(type); item.setQuantity(d(quantity)); item.setUnitPrice(d(price));
    item.setAmount(d(quantity).multiply(d(price))); item.setSettlementKey("P/" + code); return item;
  }
  MonthlyPreparationInputs inputs(String path, Map<MonthlyPriceRequest, Selection> selected, List<Use> uses) {
    return MonthlyPreparationInputs.capture(new MonthlyAdoptionPlan(selected, uses, Set.of()), path);
  }
  String input(MonthlyPriceRequest request, Selection selected, String p0, String p1) {
    return codec.encode(MonthlyPreparationPrice.read(new QuotationPriceSession.Value(request, selected, d(p0), d(p1))));
  }
  Selection selected(long id, boolean household, String price) {
    var source = SourceEvidence.ofJson("{\"source\":\"saved-projection-test\"}");
    var fact = household ? PriceFact.household(d(price), d(".085"), source) : PriceFact.normal(d(price), source, Set.of());
    return new Selection(id, new MonthlyPriceCoverage(Set.of("210", "220"), QuantityInterval.all(), ConditionDomain.any()), fact);
  }
  MonthlyPriceRequest request(String code, String org) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", code,
        null, "件", "CNY", TaxBasis.EXCLUDED, null), org, NonCuConditions.empty(), BigDecimal.ONE);
  }
  static BigDecimal d(String value) { return new BigDecimal(value); }
}
