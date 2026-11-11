package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MakePartQuotedWeightTest {
  final ObjectMapper json = new ObjectMapper();
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(json);

  @Test void originalSelectedFormulaUsesGramsOnceAndIgnoresOtherSupplierCandidates() throws Exception {
    var selected = json.createObjectNode().put("materialCode", "201502458");
    selected.put("sourceInputSnapshotJson", """
        {"rule":{"sourceFormula":"ROUND((A285*0.75+M285),4)*($L$285)/1000/1.13",
          "compiled":{"sources":[
            {"address":{"cell":"L285","sheet":"Sheet1"},"rawValue":"298.14"},
            {"address":{"cell":"M285","sheet":"Sheet1"},"rawValue":"3.8"}]}}}
        """);
    com.fasterxml.jackson.databind.node.ObjectNode source = json.createObjectNode().set("selected", selected);
    source.putArray("candidates").addObject().put("netWeight", "999");
    var weight = MakePartQuotedWeight.read(price("LINKED", source), codec);
    assertThat(weight.grams()).isEqualByComparingTo("298.14");
    assertThat(weight.source()).isEqualTo("SRM联动价表");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"L285", "Sheet1!L285", "'Sheet1'!$L$285", "(Sheet1!$L$285)"})
  void explicitSheetAndAbsoluteReferencesKeepTheCapturedWeight(String reference) throws Exception {
    var original = json.createObjectNode();
    var rule = original.putObject("rule").put("sheet", "Sheet1").put("sourceFormula", "A1*"+reference+"/1000/1.13");
    var input = rule.putObject("compiled").putArray("sources").addObject().put("rawValue", "298.14");
    input.putObject("address").put("cell", "L285").put("sheet", "Sheet1");
    var source = json.createObjectNode();
    source.putObject("selected").put("materialCode", "201502458").put("sourceInputSnapshotJson", json.writeValueAsString(original));
    assertThat(MakePartQuotedWeight.read(price("LINKED", source), codec).grams()).isEqualByComparingTo("298.14");
  }

  @Test void rangeUsesOnlySelectedBandAndZeroIsValid() throws Exception {
    for (String grams : List.of("298.14", "0")) {
      var source = json.createObjectNode().put("selectedBandId", 7);
      source.putObject("selected").put("materialCode", "201502458");
      source.putArray("bands").addObject().put("id", 6).put("netWeight", "999");
      source.withArray("bands").addObject().put("id", 7).put("netWeight", grams);
      var weight = MakePartQuotedWeight.read(price("RANGE_FACTOR", source), codec);
      assertThat(weight.grams()).isEqualByComparingTo(grams);
      assertThat(weight.source()).isEqualTo("SRM区间价表");
    }
  }

  @Test void originalRangeFormulaReadsOnlyTheSelectedBandCapturedInputs() throws Exception {
    var source = json.createObjectNode().put("selectedBandId", 7);
    source.putObject("selected").put("materialCode", "201502458").put("sourceInputSnapshotJson", """
        {"sources":[{"bands":[{"address":{"cell":"H8","sheet":"矩阵"},
          "source":{"formula":"A8*L8/1000"},
          "compiled":{"sources":[{"address":{"cell":"L8","sheet":"矩阵"},"rawValue":"301.5"}]}},
          {"address":{"cell":"I8","sheet":"矩阵"},
          "source":{"formula":"A8*M8/1000"},
          "compiled":{"sources":[{"address":{"cell":"M8","sheet":"矩阵"},"rawValue":"999"}]}}]}]}
        """);
    source.putArray("bands").addObject().put("id", 7).put("sourceCellRef", "H8").put("sourceSheetName", "矩阵");
    var weight = MakePartQuotedWeight.read(price("RANGE_FACTOR", source), codec);
    assertThat(weight.grams()).isEqualByComparingTo("301.5");
    assertThat(weight.source()).isEqualTo("SRM区间价表");
  }

  @Test void missingNegativeUnknownAndWrongMaterialDoNotBorrowWeight() throws Exception {
    for (String kind : List.of("LINKED", "RANGE_FACTOR", "FIXED")) {
      var source = json.createObjectNode();
      source.putObject("selected").put("materialCode", "201502458").put("netWeight", "-1");
      assertThat(MakePartQuotedWeight.read(price(kind, source), codec)).isNull();
      source.withObject("/selected").put("materialCode", "OTHER").put("netWeight", "298.14");
      assertThat(MakePartQuotedWeight.read(price(kind, source), codec)).isNull();
    }
    assertThat(MakePartQuotedWeight.read(null, codec)).isNull();
  }

  @Test void duplicateConversionsCannotBeGuessedAsWeight() throws Exception {
    var source = json.createObjectNode();
    source.putObject("selected").put("materialCode", "201502458")
        .put("sourceInputSnapshotJson", """
        {"rule":{"sourceFormula":"A1*L1/1000+X1*M1/1000", "compiled":{"sources":[
          {"address":{"cell":"L1"},"rawValue":"298.14"},
          {"address":{"cell":"M1"},"rawValue":"88000"}]}}}
        """);
    assertThat(MakePartQuotedWeight.read(price("LINKED", source), codec)).isNull();
  }

  MakePartMaterialPriceResolveResult price(String kind, com.fasterxml.jackson.databind.node.ObjectNode source) throws Exception {
    var snapshot = json.createObjectNode().put("sourceKind", kind).set("source", source);
    var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11),
        "COMMERCIAL", "201502458", null, "只", "CNY", TaxBasis.EXCLUDED, null),
        "210", NonCuConditions.empty(), BigDecimal.ONE);
    var fact = PriceFact.normal(BigDecimal.TEN, SourceEvidence.ofJson(json.writeValueAsString(snapshot)), Set.of());
    var selection = new Selection(42L, new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()), fact);
    var result = MakePartMaterialPriceResolveResult.ok("201502458", "NORMAL", BigDecimal.TEN, null, null);
    result.setEvidence(new PriceResolveEvidence(42L, null, null, null, null, null, null, null,
        false, null, codec.encode(MonthlyPreparationPrice.read(new QuotationPriceSession.Value(
            request, selection, BigDecimal.TEN, BigDecimal.TEN)))));
    return result;
  }
}
