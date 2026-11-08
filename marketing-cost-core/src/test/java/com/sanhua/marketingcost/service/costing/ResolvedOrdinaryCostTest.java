package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResolvedOrdinaryCostTest {
  @Test void decimalScaleDoesNotPreventReuseButEvenSubDoublePrecisionDifferencesRemainDistinct() {
    var a = rows(7); var b = rows(8);
    a.getFirst().setAmount(d("100.000000000000000001"));
    b.getFirst().setAmount(d("100.00000000000000000100"));
    var first = new ResolvedOrdinaryCost(a,d("4"),true);
    var second = new ResolvedOrdinaryCost(b,d("4.000"),true);
    var calculated = ResolvedOrdinaryCost.calculate(first.inputs());
    assertThat(second.bind(calculated).getFirst().getSourceId()).isEqualTo(8L);
    b.getFirst().setAmount(d("100.000000000000000002"));
    assertThatThrownBy(() -> new ResolvedOrdinaryCost(b,d("4.000"),true).bind(calculated))
        .hasMessageContaining("inputs differ");
    b.getFirst().setAmount(d("100.00000000000000000100")); b.getLast().setRate(d("0"));
    assertThatThrownBy(() -> new ResolvedOrdinaryCost(b,d("4.000"),true).bind(calculated))
        .hasMessageContaining("inputs differ");
  }
  static BigDecimal d(String value) { return new BigDecimal(value); }
  static List<CostRunCostItemDto> rows(long source) {
    var rows = new ArrayList<CostRunCostItemDto>();
    for (String code : List.of("MATERIAL", "DIRECT_LABOR", "INDIRECT_LABOR", "LOSS", "MANUFACTURE",
        "MANUFACTURE_COST", "ADJUSTED_MANUFACTURE_COST", "MGMT_EXP", "SALES_EXP", "FIN_EXP", "TOTAL", "BOM_BUCKET_WELD")) {
      var row = new CostRunCostItemDto(); row.setCostCode(code); row.setCostName(code);
      row.setCategory(code.startsWith("BOM_BUCKET") ? "BOM_BUCKET" : "EXPENSE");
      row.setSourceTable("lp_quote_tech_data_version"); row.setSourceId(source); row.setRemark("source " + source);
      row.setRate(switch (code) {
        case "LOSS" -> d(".01"); case "MANUFACTURE" -> d(".20"); case "ADJUSTED_MANUFACTURE_COST" -> d("1.05");
        case "MGMT_EXP" -> d(".10"); case "SALES_EXP" -> d(".25"); case "FIN_EXP" -> d(".02"); default -> null;
      });
      row.setAmount(switch (code) {
        case "MATERIAL" -> d("100"); case "DIRECT_LABOR" -> d("10"); case "INDIRECT_LABOR" -> d("2");
        case "BOM_BUCKET_WELD" -> d("3"); default -> null;
      }); rows.add(row);
    }
    return rows;
  }

  @Test void originalFormulaRunsOnImmutableNumbersAndBothConsumersKeepTheirOwnSources() {
    var firstRows = rows(7); var secondRows = rows(8);
    var first = new ResolvedOrdinaryCost(firstRows, d("4"), true);
    var second = new ResolvedOrdinaryCost(secondRows, d("4"), true);
    assertThat(second.inputs()).isEqualTo(first.inputs());
    assertThat(first.inputs().rows().stream().filter(row -> "TOTAL".equals(row.code())).findFirst().orElseThrow().amount()).isNull();
    firstRows.getFirst().setAmount(d("999")); secondRows.getFirst().setSourceId(999L);
    var values = ResolvedOrdinaryCost.calculate(first.inputs());
    var bound = second.bind(values);
    assertThat(bound).allSatisfy(row -> assertThat(row.getSourceId()).isEqualTo(8));
    assertThat(bound.stream().filter(row -> "TOTAL".equals(row.getCostCode())).findFirst().orElseThrow().getAmount())
        .isEqualByComparingTo("207.403900");
    bound.getFirst().setAmount(d("-1"));
    assertThat(second.bind(values).getFirst().getAmount()).isEqualByComparingTo("100");
    assertThat(first.bind(values)).allSatisfy(row -> assertThat(row.getSourceId()).isEqualTo(7));
  }

  @Test void equalTotalsWithDifferentDisplayComponentsOrMissingRequiredFeesCannotReuse() {
    var a = new ResolvedOrdinaryCost(rows(7), d("0"), true);
    var altered = rows(8); altered.getLast().setAmount(d("4"));
    var b = new ResolvedOrdinaryCost(altered, d("0"), true);
    assertThatThrownBy(() -> b.bind(ResolvedOrdinaryCost.calculate(a.inputs()))).hasMessageContaining("inputs differ");
    var missing = new ResolvedOrdinaryCost(rows(8), d("0"), false);
    assertThatThrownBy(() -> missing.bind(ResolvedOrdinaryCost.calculate(a.inputs()))).hasMessageContaining("inputs differ");
    assertThat(missing.bind(ResolvedOrdinaryCost.calculate(missing.inputs())).stream()
        .filter(row -> "TOTAL".equals(row.getCostCode())).findFirst().orElseThrow().getAmount()).isNull();
  }

  @Test void bindingPreservesEachOaAndPartAndDoesNotExposeMutableCapturedState() {
    var a = CostRunContext.quote("OA-A", 1L, "P", "BOX", "Customer A", "COMMERCIAL", "2026-11", "A");
    a.setCostRunVersionId(1L);
    var b = CostRunContext.quote("OA-B", 2L, "P", "BOX", "Customer B", "COMMERCIAL", "2026-11", "B");
    b.setCostRunVersionId(2L);
    var part = new CostRunPartItemDto(); part.setProductCode("P"); part.setOaNo("OA-B"); part.setBomRowId(22L);
    var first = new ResolvedCostRun(a, List.of(), new ResolvedOrdinaryCost(rows(7), d("4"), true));
    var second = new ResolvedCostRun(b, List.of(part), new ResolvedOrdinaryCost(rows(8), d("4"), true));
    b.setOaNo("MUTATED"); part.setBomRowId(999L); second.context().setOaNo("MUTATED-AGAIN");
    var numbers = ResolvedOrdinaryCost.calculate(first.inputs()); var result = second.bind(numbers);
    assertThat(result.getContext().getOaNo()).isEqualTo("OA-B");
    assertThat(result.getSourceCostVersionId()).isEqualTo(2L);
    assertThat(result.getResult().getCustomerName()).isEqualTo("Customer B");
    assertThat(result.getPartItems().getFirst().getBomRowId()).isEqualTo(22L);
    result.getPartItems().getFirst().setBomRowId(0L);
    assertThat(second.bind(numbers).getPartItems().getFirst().getBomRowId()).isEqualTo(22L);
  }

  @Test void repeatedAuxiliarySubjectRowsRemainSeparateAndInOriginalOrder() {
    var input = rows(7);
    for (String amount : List.of("1.25", "2.75")) {
      var row = new CostRunCostItemDto(); row.setCostCode("AUX_SAME"); row.setCostName("辅料");
      row.setAmount(d(amount)); row.setSourceId((long) input.size()); input.add(row);
    }
    var resolved = new ResolvedOrdinaryCost(input, d("4"), true);
    var bound = resolved.bind(ResolvedOrdinaryCost.calculate(resolved.inputs()));
    assertThat(bound.stream().filter(row -> "AUX_SAME".equals(row.getCostCode())))
        .extracting(CostRunCostItemDto::getAmount).containsExactly(d("1.25"), d("2.75"));
    assertThat(bound.stream().filter(row -> "AUX_SAME".equals(row.getCostCode())))
        .extracting(CostRunCostItemDto::getSourceId).containsExactly(12L, 13L);
  }
}
