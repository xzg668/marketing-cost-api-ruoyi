package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceScenarios.Pair;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class JjbMaterialBasePricesTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static BigDecimal d(String s) { return new BigDecimal(s); }
  PriceFactorContext context(String copper) {
    var base = factors("90", "20.831", "52.623", "54.897");
    var band = new FactorPriceSnapshot.CuBand("CU_100000", d("100000"), factors(copper, "23.146", "58.470", "60.996"));
    var snapshot = new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL", "210", "2026-11", "CU_90000", FactorPriceScope.Condition.GENERAL),
        1L, 1, "finance.xls", "sha", "content", base, List.of("CU_90000", "CU_100000"), "finance", null, null, List.of(band));
    return new PriceFactorContext("PART", new PriceMonthSelection("2026-11", "2026-11"), snapshot, null)
        .withCustomerMaterials(CustomerMaterialPrices.copperAndZinc(d("105000"), d("999999")));
  }
  Map<String, FactorPriceSnapshot.FactorValue> factors(String cu, String zn, String brass, String h65) {
    var result = new LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
    String[] keys = {PriceFactorContext.COPPER, PriceFactorContext.ZINC, "AVG|美国柜装黄铜", "AVG|H65黄铜边料"};
    String[] amounts = {cu, zn, brass, h65};
    for (int i=0; i<keys.length; i++) result.put(keys[i], new FactorPriceSnapshot.FactorValue(1L, 2L,
        keys[i], keys[i], d(amounts[i]), "CNY/KG", true, List.of()));
    return result;
  }
  Pair pair(PriceFactorContext context) throws Exception {
    return new Pair(1L, "source", d("1"), d("2"), json.writeValueAsString(Map.of(
        "customerMaterialPrices", context.customerMaterialPrices(), "customerFactorContexts", List.of(context))));
  }
  @Test void headerUsesCapturedBandAndKeepsOriginalCustomerCopperSeparate() throws Exception {
    var header = new LinkedHashMap<>(Map.of("copperPrice", "999000", "zincPrice", "999999", "customerCuPrice", "999"));
    var warnings = new ArrayList<String>();
    new JjbMaterialBasePrices(json).apply(header, List.of(pair(context("100"))), warnings);
    assertThat(header).containsEntry("copperPrice", "100000.000000").containsEntry("customerCuPrice", "105.000000")
        .containsEntry("zincPrice", "23146.000000").containsEntry("brassPrice", "58.470000")
        .containsEntry("brassScrapPrice", "60.996000").doesNotContainKeys("sus304Price", "silverPrice");
    assertThat(warnings).isEmpty();
  }
  @Test void conflictingSavedPublicationsAreNotPresentedAsOneFalseHeaderPrice() throws Exception {
    var header = new LinkedHashMap<String, String>(); var warnings = new ArrayList<String>();
    new JjbMaterialBasePrices(json).apply(header, List.of(pair(context("100")), pair(context("101"))), warnings);
    assertThat(header).doesNotContainKey("copperPrice").containsEntry("brassPrice", "58.470000");
    assertThat(warnings).hasSize(1).first().asString().contains("多份不同基价");
  }
  @Test void fixedAndLinkedSalesHeadersUseCustomerCopperBandWithoutChangingFormulaInputs() throws Exception {
    for (String mode : List.of("固定", "销售价格固定", "联动", "销售价格联动")) {
      var form = new com.sanhua.marketingcost.entity.OaForm();
      form.setCopperPrice(d("105000")); form.setZincPrice(d("24000")); form.setPriceLinkMode(mode);
      var input = context("100").withCustomerMaterials(CustomerMaterialPrices.fromQuotation(form));
      var before = json.writeValueAsString(input);
      var header = new LinkedHashMap<String, String>();
      new JjbMaterialBasePrices(json).apply(header, List.of(pair(input)), new ArrayList<>());
      boolean fixed = mode.endsWith("固定");
      assertThat(header).containsEntry("priceLinkMode", mode).containsEntry("customerCuPrice", "105.000000")
          .containsEntry("copperPrice", "100000.000000")
          .containsEntry("zincPrice", "23146.000000")
          .containsEntry("brassPrice", "58.470000");
      assertThat(input.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo(fixed ? "100" : "105");
      assertThat(input.requiredPrice(PriceFactorContext.ZINC, FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo(fixed ? "23.146" : "24");
      assertThat(json.writeValueAsString(input)).isEqualTo(before);
    }
  }
  @Test void oldContextUsesCustomerBandForHeaderButKeepsSavedFormulaSemantics() throws Exception {
    var tree = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(context("100"));
    tree.remove("substitutionVersion");
    var old = json.treeToValue(tree, PriceFactorContext.class);
    var header = new LinkedHashMap<String, String>();
    new JjbMaterialBasePrices(json).apply(header, List.of(pair(old)), new ArrayList<>());
    assertThat(header).containsEntry("copperPrice", "100000.000000").containsEntry("zincPrice", "23146.000000")
        .containsEntry("brassPrice", "58.470000");
    assertThat(old.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("105");
    assertThat(old.requiredPrice(PriceFactorContext.ZINC, FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("999.999");
  }
  @Test void fixedSalesWithCustomerCu120Shows120BandInHeaderWhileSavedCalculationStaysAt90() throws Exception {
    var original = context("100");
    var high = new FactorPriceSnapshot.CuBand("CU_120000", d("120000"),
        factors("120", "27.7747505548238", "70.1643721920502", "73.1956047128448"));
    var financial = original.financial().withCuBands(List.of(high));
    // The original V6 fixed-sale calculation saved the OA fields but no selected band.
    var prices = new CustomerMaterialPrices(d("120000"), d("24000"), null, null, null, null, "固定");
    var input = new PriceFactorContext("PART", original.months(), financial, null, null, null, prices, null,
        "MONTHLY_MATERIAL_CU_BANDS_V6");
    var saved = pair(input);
    var header = new LinkedHashMap<String, String>();
    new JjbMaterialBasePrices(json).apply(header, List.of(saved), new ArrayList<>());
    assertThat(header).containsEntry("copperPrice", "120000.000000")
        .containsEntry("customerCuPrice", "120.000000").containsEntry("zincPrice", "27774.750555")
        .containsEntry("brassPrice", "70.164372").containsEntry("brassScrapPrice", "73.195605");
    assertThat(input.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true)).isEqualByComparingTo("90");
    assertThat(saved.financialPrice()).isEqualByComparingTo("1");
    assertThat(saved.customerPrice()).isEqualByComparingTo("2");
  }
  @Test void missingSelectedBandFactorStaysBlankInsteadOfLeakingBaselineOrDemandZinc() throws Exception {
    var original = context("100");
    var band = original.financial().cuBands().getFirst().retain(Set.of(PriceFactorContext.COPPER));
    var input = new PriceFactorContext("PART", original.months(), original.financial().withCuBands(List.of(band)), null)
        .withCustomerMaterials(original.customerMaterialPrices());
    var header = new LinkedHashMap<>(Map.of("zincPrice", "999999", "brassPrice", "52.623", "brassScrapPrice", "54.897"));
    new JjbMaterialBasePrices(json).apply(header, List.of(pair(input)), new ArrayList<>());
    assertThat(header).containsEntry("copperPrice", "100000.000000")
        .doesNotContainKeys("zincPrice", "brassPrice", "brassScrapPrice");
  }
  @Test void noCustomerCopperKeepsAvailableFinancialHeader() throws Exception {
    var input = context("100").withCustomerMaterials(new CustomerMaterialPrices(null, null, null, null, null, null, "固定"));
    var header = new LinkedHashMap<String, String>();
    new JjbMaterialBasePrices(json).apply(header, List.of(pair(input)), new ArrayList<>());
    assertThat(header).containsEntry("copperPrice", "90000.000000").containsEntry("zincPrice", "20831.000000")
        .containsEntry("brassPrice", "52.623000").doesNotContainKey("customerCuPrice");
  }
}
