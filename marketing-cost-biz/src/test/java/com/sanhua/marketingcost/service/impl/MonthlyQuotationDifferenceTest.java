package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartProcessTypePolicy;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.*;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyQuotationDifferenceTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(json);
  final PricePrepareItemMapper preparations = mock(PricePrepareItemMapper.class);
  final MonthlyQuotationCosting service = new MonthlyQuotationCosting(preparations,
      mock(MonthlyCostVersionService.class), codec, json);
  final List<PricePrepareItem> saved = new ArrayList<>();
  final Map<String, PreparationBinding> bindings = new HashMap<>();

  @Test void zeroDifferenceStillMarksCopperAndHouseholdIsAlwaysZeroAndNoncontributing() {
    var a = request("A"); var h = request("HH");
    var normal = price(1, false, true, "10"); var household = price(2, true, false, "20");
    var plan = new MonthlyAdoptionPlan(Map.of(a, normal, h, household),
        List.of(new Use("P/A", a, d("2")), new Use("P/HH", h, d("3"))), Set.of());
    preparePair("P/A", "A", "MONTHLY_PRICE", d("2"), d("10"), d("10"));
    preparePair("P/HH", "HH", "MONTHLY_PRICE", d("3"), d("20"), d("20"));
    var result = service.difference(stored(plan, List.of(new MaterialLine("P/A", "A", d("2"),
        List.of(1L), d("20"), d("20"), null))));
    assertThat(result.adjustmentAmount()).isZero();
    assertThat(result.settlementCount()).isEqualTo(2); assertThat(result.cuAffectedSettlementCount()).isEqualTo(1);
    var copper = result.items().getFirst(); assertThat(copper.getCuAffected()).isEqualTo(1);
    assertThat(copper.getDiffAmount()).isZero();
    var hh = result.items().getLast(); assertThat(hh.getCuAffected()).isZero();
    assertThat(hh.getDiffAmount()).isZero(); assertThat(hh.getContributesToAdjustment()).isZero();
    assertThat(hh.getOaAmount()).isEqualByComparingTo("60");
    verify(preparations).selectBatchIds(anyCollection()); verifyNoMoreInteractions(preparations);
  }

  @Test void savedMakeComponentsExplainRawAndScrapWithoutDoubleCountingParent() {
    var raw = request("RAW"); var scrap = request("SCRAP");
    var rawPrice = price(1, false, true, "10"); var scrapPrice = price(2, false, false, "2");
    var plan = new MonthlyAdoptionPlan(Map.of(raw, rawPrice, scrap, scrapPrice),
        List.of(new Use("P/MAKE/RAW", raw, d("2")), new Use("P/MAKE/SCRAP", scrap, d(".4"))), Set.of());
    var pair = preparePair("P/MAKE", "MAKE", "MAKE_PART_PRICE", d("2"), d("9.6"), d("11.6"));
    for (int i = 0; i < pair.size(); i++) {
      var row = new MakePartPriceCalcRow(); row.setId(100L + i); row.setParentMaterialNo("MAKE");
      row.setBusinessUnitType("COMMERCIAL"); row.setPricingMonth("2026-10"); row.setChildMaterialNo("RAW");
      row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW); row.setGrossWeightG(d("1000"));
      row.setNetWeightG(d("800")); row.setScrapCode("SCRAP"); row.setNoScrapConfirmed(false);
      row.setRawUnitPrice(i == 0 ? d("10") : d("12")); row.setScrapUnitPrice(d("2"));
      row.setRawInputSnapshotJson(input(raw, rawPrice, "10", "12"));
      row.setScrapInputSnapshotJson(input(scrap, scrapPrice, "2", "2"));
      var parent = new BomCostingRow(); parent.setId(9L); parent.setMaterialCode("MAKE");
      parent.setBusinessUnitType("COMMERCIAL"); parent.setPriceOrgCode("210"); parent.setPeriodMonth("2026-10");
      pair.get(i).setInputSnapshotJson(new MakePartCalculationEvidence(json)
          .capture(parent, new MakePartPriceCalculator().calculate(List.of(row))));
    }
    var result = service.difference(stored(plan, List.of(new MaterialLine("P/MAKE", "MAKE", d("2"),
        List.of(1L, 2L), d("19.2"), d("23.2"), null))));
    assertThat(result.adjustmentAmount()).isEqualByComparingTo("4");
    assertThat(result.rawComponentCount()).isEqualTo(1); assertThat(result.cuAffectedSettlementCount()).isEqualTo(1);
    var child = result.items().getLast(); assertThat(child.getDetailLevel()).isEqualTo("RAW_COMPONENT");
    assertThat(child.getContributesToAdjustment()).isZero(); assertThat(child.getDiffAmount()).isEqualByComparingTo("4");
    assertThat(child.getParentSettlementKey()).isEqualTo("P/MAKE");
    assertThat(child.getPriceFormulaRefType()).isEqualTo("MAKE_PART_COMPONENT");
    assertThat(child.getTraceJson()).contains("monthlyPriceIds", "grossWeightG", "scrapUnitPrice");
    assertThat(result.items().stream().filter(row -> row.getContributesToAdjustment() == 1)
        .map(row -> row.getDiffAmount()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("4");
  }

  @Test void savedPackageExplanationUsesBothDenominatorsAndKeepsCopperAtZeroDifference() {
    var child = request("CHILD"); var selected = price(1, false, true, "10");
    var plan = new MonthlyAdoptionPlan(Map.of(child, selected), List.of(new Use("P/PACK/CHILD", child, d("1.5"))), Set.of());
    var pair = preparePair("P/PACK", "PACK", "PACKAGE_PRICE", d("3"), d("5"), d("5"));
    var parent = new PackageComponentPrice(); parent.setPackageMaterialCode("PACK");
    parent.setBusinessUnitType("COMMERCIAL"); parent.setPriceOrgCode("210"); parent.setPeriodMonth("2026-10");
    parent.setSnapshotId(100L); parent.setPackageParentBaseQty(d("4"));
    var component = new PackageComponentPriceDetail(); component.setSnapshotDetailId(101L);
    component.setChildMaterialCode("CHILD"); component.setPriceSource("MONTHLY_PRICE"); component.setPriceType("RANGE");
    component.setQtyPerParent(d("4")); component.setChildParentBaseQty(d("2"));
    component.setChildUnitPrice(d("10")); component.setChildAmount(d("20"));
    component.setInputSnapshotJson(input(child, selected, "10", "10"));
    pair.forEach(row -> row.setInputSnapshotJson(new PackageCalculationEvidence(json).capture(parent, List.of(component))));
    var result = service.difference(stored(plan, List.of(new MaterialLine("P/PACK", "PACK", d("3"),
        List.of(1L), d("15"), d("15"), null))));
    assertThat(result.adjustmentAmount()).isZero(); assertThat(result.rawComponentCount()).isEqualTo(1);
    var explained = result.items().getLast(); assertThat(explained.getQuantity()).isEqualByComparingTo("1.5");
    assertThat(explained.getOaAmount()).isEqualByComparingTo("15");
    assertThat(explained.getCuAffected()).isEqualTo(1); assertThat(explained.getContributesToAdjustment()).isZero();
    assertThat(explained.getPriceFormulaRefType()).isEqualTo("PACKAGE_COMPONENT");
  }

  @Test void packageOccurrencesWithoutSharedSnapshotRetainDistinctOriginalChildIdentities() {
    var first = request("C1"); var second = request("C2");
    var p1 = price(1, false, true, "10"); var p2 = price(2, false, false, "20");
    var plan = new MonthlyAdoptionPlan(Map.of(first, p1, second, p2), List.of(
        new Use("P/PACK/C1", first, d("1.5")), new Use("P/PACK/C2", second, d("1.5"))), Set.of());
    var pair = preparePair("P/PACK", "PACK", "PACKAGE_PRICE", d("3"), d("15"), d("15"));
    var parent = new PackageComponentPrice(); parent.setPackageMaterialCode("PACK");
    parent.setBusinessUnitType("COMMERCIAL"); parent.setPriceOrgCode("210"); parent.setPeriodMonth("2026-10");
    parent.setSourceRawHierarchyId(100L); parent.setSourcePath("/P/PACK/"); parent.setPackageParentBaseQty(d("4"));
    var rows = new ArrayList<PackageComponentPriceDetail>();
    for (int i = 0; i < 2; i++) {
      var code = i == 0 ? "C1" : "C2"; var amount = i == 0 ? "10" : "20";
      var row = new PackageComponentPriceDetail(); row.setSourceHierarchyId(101L + i);
      row.setSourcePath("/P/PACK/" + code + "/"); row.setChildMaterialCode(code);
      row.setPriceSource("MONTHLY_PRICE"); row.setPriceType("NORMAL");
      row.setQtyPerParent(d("4")); row.setChildParentBaseQty(d("2"));
      row.setChildUnitPrice(d(amount)); row.setChildAmount(d(amount).multiply(d("2")));
      row.setInputSnapshotJson(input(i == 0 ? first : second, i == 0 ? p1 : p2, amount, amount)); rows.add(row);
    }
    pair.forEach(row -> row.setInputSnapshotJson(new PackageCalculationEvidence(json).capture(parent, rows)));
    var result = service.difference(stored(plan, List.of(new MaterialLine("P/PACK", "PACK", d("3"),
        List.of(1L, 2L), d("45"), d("45"), null))));
    assertThat(result.rawComponentCount()).isEqualTo(2);
    assertThat(result.items().subList(1, 3)).extracting(row -> row.getMaterialCode()).containsExactly("C1", "C2");
    assertThat(result.items().subList(1, 3)).allSatisfy(row -> {
      assertThat(row.getPriceFormulaRefId()).isNull();
      assertThat(row.getQuantity()).isEqualByComparingTo("1.5");
      assertThat(row.getTraceJson()).contains("sourceHierarchyId", "sourcePath");
    });
    assertThat(result.items().get(1).getSettlementKey()).isNotEqualTo(result.items().get(2).getSettlementKey());
    assertThat(result.adjustmentAmount()).isZero();
  }

  @Test void nonCopperDifferenceFailsClosedInsteadOfInventingCopperDependency() {
    var a = request("A"); var selected = price(1, false, false, "10");
    preparePair("P/A", "A", "MONTHLY_PRICE", BigDecimal.ONE, d("10"), d("12"));
    var plan = new MonthlyAdoptionPlan(Map.of(a, selected), List.of(new Use("P/A", a, BigDecimal.ONE)), Set.of());
    var stored = stored(plan, List.of(new MaterialLine("P/A", "A", BigDecimal.ONE, List.of(1L), d("10"), d("12"), null)));
    assertThatThrownBy(() -> service.difference(stored)).hasMessageContaining("Non-Cu");
  }

  Stored stored(MonthlyAdoptionPlan plan, List<MaterialLine> lines) {
    var version = new QuoteCostRunVersion(); version.setId(9L); version.setCostRunNo("COST");
    version.setProductCode("P"); version.setOaNo("OA"); version.setOaFormItemId(2L);
    version.setPricingMonth("2026-10"); version.setBusinessUnitType("COMMERCIAL");
    var ordinary = new Ordinary(lines, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    var calculation = MonthlyProductCost.calculate(plan, ordinary, null);
    when(preparations.selectBatchIds(anyCollection())).thenReturn(saved);
    return new Stored(version, new Snapshot(1, "COMMERCIAL", 9, "COST", "OA", 2, "P", "2026-10",
        calculation, List.of(), bindings), List.of(), List.of());
  }
  List<PricePrepareItem> preparePair(String path, String code, String type, BigDecimal quantity, BigDecimal p0, BigDecimal p1) {
    var pair = new ArrayList<PricePrepareItem>();
    for (var price : List.of(p0, p1)) {
      var row = new PricePrepareItem(); row.setId((long) saved.size() + 1); row.setOaFormItemId(2L);
      row.setBusinessUnitType("COMMERCIAL"); row.setPeriodMonth("2026-10"); row.setPriceOrgCode("210");
      row.setMaterialCode(code); row.setResultRefType(type); row.setQuantity(quantity);
      row.setUnitPrice(price); row.setAmount(price.multiply(quantity)); saved.add(row); pair.add(row);
    }
    bindings.put(path, new PreparationBinding(pair.getFirst().getId(), pair.getLast().getId())); return pair;
  }
  Selection price(long id, boolean household, boolean copper, String price) {
    String kind = household ? "HOUSEHOLD_INQUIRY" : copper ? "RANGE_LEGACY_FACTOR" : "PURCHASE_FIXED";
    Object source = copper ? Map.of("factorBinding", Map.of("canonicalKey", PriceFactorContext.COPPER)) : Map.of();
    var evidence = SourceEvidence.ofJson(codec.unwrap(codec.encode(Map.of("snapshotSchema", 1, "sourceKind", kind, "source", source))));
    var fact = household ? PriceFact.household(d(price), d(".085"), evidence) : PriceFact.normal(d(price), evidence, Set.of());
    return new Selection(id, new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()), fact);
  }
  String input(MonthlyPriceRequest request, Selection selected, String p0, String p1) {
    return codec.encode(MonthlyPreparationPrice.read(new QuotationPriceSession.Value(request, selected, d(p0), d(p1))));
  }
  static MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", code,
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }
  static BigDecimal d(String value) { return new BigDecimal(value); }
}
