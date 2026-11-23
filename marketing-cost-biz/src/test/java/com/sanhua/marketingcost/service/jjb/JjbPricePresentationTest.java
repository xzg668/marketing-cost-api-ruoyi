package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.AdoptedPrice;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JjbPricePresentationTest {
  private final ObjectMapper json = new ObjectMapper();
  @ParameterizedTest
  @CsvSource({"PURCHASE_FIXED,固定价", "LINKED,联动价", "LINKED_LEGACY,联动价",
      "RANGE_FACTOR,区间价", "RANGE_LEGACY_FACTOR,区间价", "RANGE_QTY,区间价",
      "ODS_TIER,阶梯价", "INQUIRY,询价", "HOUSEHOLD_INQUIRY,询价（家用部件）",
      "INVOICE,发票价格", "ODS_ORDER,订单价", "TECH_FIXED,固定价（技术补录）"})
  void savedMonthlyEvidenceShowsItsOriginalSourceAndExactQuantityUnit(String kind, String expected) throws Exception {
    var row = JjbReadModel.Row.builder().key("PART:1").code("M").kind("PART")
        .quantity("0.00001").unitPrice("1259.115000").amount("0.012591").priceSource("月度物料价")
        .sources(List.of(new JjbReadModel.Source("lp_price_prepare_item", 7L, "已保存"))).build();
    var prepared = preparation(kind, "千克", "M", 1);
    var result = new JjbPricePresentation(json).apply(List.of(row), null, List.of(prepared)).getFirst();
    assertThat(result.priceSource()).isEqualTo(expected);
    assertThat(result.unit()).isEqualTo("千克");
    assertThat(result.quantity()).isEqualTo("0.00001");
    assertThat(result.amount()).isEqualTo(row.amount());
    assertThat(result.sources()).isEqualTo(row.sources());
  }

  @Test void anotherMaterialOrChildPriceConversionCannotSupplyAParentUnit() throws Exception {
    var row = JjbReadModel.Row.builder().key("PART:1").code("M").kind("PART")
        .priceSource("自制件价格生成").sources(List.of(new JjbReadModel.Source("lp_price_prepare_item",7L,"依据"))).build();
    for (var prepared : List.of(preparation("PURCHASE_FIXED","KG","CHILD",1),preparation("PURCHASE_FIXED","KG","M",1000))) {
      var result = new JjbPricePresentation(json).apply(List.of(row), null, List.of(prepared)).getFirst();
      assertThat(result.unit()).isNull();
      assertThat(result.priceSource()).isEqualTo("自制件价格生成");
    }
  }

  @ParameterizedTest
  @CsvSource({"SETTLE_LINKED,0.119,0.119", "SETTLE_FIXED,0,0", "SETTLE_LINKED,0.119000000000,0.119"})
  void showsOnlySavedSettlementCopperWithoutChangingNetWeightOrAmounts(String kind, String weight, String expected) throws Exception {
    var row = JjbReadModel.Row.builder().code("M").kind("PART").netWeight("5")
        .quantity("2").unitPrice("0.023200").amount("0.046400")
        .sources(List.of(new JjbReadModel.Source("lp_price_prepare_item", 7L, "已保存"))).build();
    var prepared = preparation(kind,"只","M",1);
    var codec = new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json);
    var captured = (com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(codec.unwrap(prepared.getInputSnapshotJson()));
    var evidence = json.createObjectNode().put("sourceKind",kind);
    evidence.putObject("source").putObject("rule").put("copperWeightG",weight).put("zincWeightG","9.876");
    ((com.fasterxml.jackson.databind.node.ObjectNode)captured.path("selection").path("fact").path("evidence")).put("json",json.writeValueAsString(evidence));
    prepared.setInputSnapshotJson(codec.wrap(json.writeValueAsString(captured)));
    var result = new JjbPricePresentation(json).apply(List.of(row),null,List.of(prepared)).getFirst();
    assertThat(result.settlementCopperWeightG()).isEqualTo(expected);
    assertThat(result.netWeight()).isEqualTo("5");
    assertThat(result.quantity()).isEqualTo("2");
    assertThat(result.unitPrice()).isEqualTo(row.unitPrice());
    assertThat(result.amount()).isEqualTo(row.amount());
    // Matching the saved preparation reference is required even when the material code matches.
    prepared.setId(8L);
    assertThat(new JjbPricePresentation(json).apply(List.of(row),null,List.of(prepared)).getFirst().settlementCopperWeightG()).isNull();
  }

  @Test void missingWeightsAndOtherPriceSourcesDoNotInventSettlementCopper() throws Exception {
    var row=JjbReadModel.Row.builder().code("M").kind("PART")
        .sources(List.of(new JjbReadModel.Source("lp_price_prepare_item",7L,"已保存"))).build();
    for(var kind:List.of("SETTLE_LINKED","SETTLE_FIXED","LINKED","PURCHASE_FIXED"))
      assertThat(new JjbPricePresentation(json).apply(List.of(row),null,List.of(preparation(kind,"只","M",1)))
          .getFirst().settlementCopperWeightG()).isNull();
  }

  @Test void recoveredMarketFactorsShowTheActuallySubstitutedPrices() {
    var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse("2026-11"),
        "COMMERCIAL", "301070047", "", "千克", "CNY", TaxBasis.EXCLUDED, null),
        "220", NonCuConditions.empty(), BigDecimal.ONE);
    var coverage = new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any());
    BigDecimal base = new BigDecimal("87.16814159"), customer = new BigDecimal("113.71681416");
    var source = new MonthlyAdoptionPlan.SourceEvidence("""
        {"sourceKind":"LINKED","source":{"selected":{"formulaExpr":"(90+8.5)/1.13"}}}
        """);
    var selection = new MonthlyAdoptionPlan.Selection(9L, coverage,
        MonthlyAdoptionPlan.PriceFact.normal(base, source, Set.of()));
    var pair = new MonthlyPriceScenarios.Pair(9L, source.sha256(), base, customer, """
        {"recoveredLinkedCalculation":{"expression":"([factor_0]+8.5)/1.13",
        "sourceTaxIncluded":false,"factorBindings":{"factor_0":{"canonicalKey":"AVG|1#CU","unit":"CNY_KG"}},
        "inputs":{"factor_0":90},"customerInputs":{"factor_0":120}}}
        """);
    var result = new JjbPriceCalculation(json).describe(new AdoptedPrice(request, selection), pair);
    assertThat(result.baseFactors()).contains("铜 90 元/公斤");
    assertThat(result.customerFactors()).contains("铜 120 元/公斤");
    assertThat(result.formula()).contains("基准：((90)+8.5)/1.13=87.16814159",
        "客户：((120)+8.5)/1.13=113.71681416");
  }

  private PricePrepareItem preparation(String kind, String unit, String code, int multiplier) throws Exception {
    var prepared = new PricePrepareItem();prepared.setId(7L);prepared.setMaterialCode(code);
    prepared.setResultRefType("MONTHLY_PRICE");prepared.setMonthlyPriceId(9L);
    String snapshot = json.writeValueAsString(Map.of("request",Map.of("family",Map.of("materialCode",code,"unit",unit)),
        "unitPriceMultiplier",multiplier,"selection",Map.of("fact",Map.of("evidence",Map.of("json",json.writeValueAsString(Map.of("sourceKind",kind)))))));
    prepared.setInputSnapshotJson(new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json).wrap(snapshot));
    return prepared;
  }
}
