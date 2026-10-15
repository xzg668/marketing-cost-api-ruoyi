package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorWorkbookParser;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 真实B01：独立POI求值原公式，期望输入按已核实源格设定，不使用被测上下文生成期望。 */
@Tag("integration")
@EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
class BoardOriginalWorkbookAcceptanceTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final FactorCanonicalKeyServiceImpl canonical =
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());

  @Test
  void all123PricesRetainRoundTaxAndIndependentCustomerTenderInputs() throws Exception {
    var baseline = json.readTree(Path.of(System.getProperty("prc.baseline")).toFile());
    var rows = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    baseline.forEach(
        row -> {
          if (row.path("sample").asText().equals("B01")) rows.add(row);
        });
    assertThat(rows).hasSize(123);
    byte[] bytes = Files.readAllBytes(Path.of(rows.getFirst().path("file").asText()));
    assertThat(PriceLinkedImportFileDigest.sha256(bytes))
        .isEqualTo(rows.getFirst().path("sha256").asText());
    com.sanhua.marketingcost.dto.FinanceFactorWorkbook finance;
    try (var input = getClass().getResourceAsStream("/finance-factor/plate-original.xlsx")) {
      finance =
          new FinanceFactorWorkbookParser(canonical)
              .parse(input.readAllBytes(), "板换.xlsx", "220", null);
    }
    assertThat(finance.problems()).isEmpty();
    Set<String> catalog = new LinkedHashSet<>();
    finance.values().forEach(v -> catalog.add(v.canonicalKey()));
    var parsed =
        new OriginalLinkedWorkbookParser(canonical).parse(bytes, "B01.xls", "COMMERCIAL", catalog);
    assertThat(parsed.errors()).isEmpty();
    assertThat(parsed.rules()).hasSize(123);
    var counts = new LinkedHashMap<String, Integer>();
    var calculator = new OriginalLinkedRuleCalculator();
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<Map<String, Object>> originalEvidence = new ArrayList<>();
    for (var rule : parsed.rules()) {
      var expected =
          rows.stream().filter(r -> r.path("row").asInt() == rule.row()).findFirst().orElseThrow();
      assertThat(rule.identity().materialCode()).isEqualTo(expected.path("materialCode").asText());
      assertThat(rule.identity().supplierCode()).isEqualTo(expected.path("supplierCode").asText());
      assertThat(rule.originalPrice())
          .isCloseTo(
              new BigDecimal(expected.path("sourceRecalculated").asText()),
              within(new BigDecimal("0.00000001")));
      originalEvidence.add(
          Map.of(
              "row",
              rule.row(),
              "material",
              rule.identity().materialCode(),
              "actual",
              rule.originalPrice(),
              "expected",
              expected.path("sourceRecalculated").asText(),
              "formula",
              rule.sourceFormula(),
              "bindings",
              rule.factors()));
      var grade = BoardStainlessGrade.fromSpecification(rule.identity().specification());
      counts.merge(grade == null ? "OTHER" : grade.name(), 1, Integer::sum);
    }
    assertThat(counts)
        .containsEntry("SUS304", 78)
        .containsEntry("SUS316", 30)
        .containsEntry("OTHER", 15);
    for (Boolean tender : Arrays.asList(null, false, true)) {
      Condition condition = Boolean.TRUE.equals(tender) ? Condition.TENDER : Condition.NON_TENDER;
      var factors = new LinkedHashMap<String, FactorPriceSnapshot.FactorValue>();
      finance.values().stream()
          .filter(v -> v.condition() == condition)
          .forEach(
              v ->
                  factors.put(
                      v.canonicalKey(),
                      new FactorPriceSnapshot.FactorValue(
                          1L,
                          1L,
                          v.factorName(),
                          v.shortName(),
                          v.price(),
                          v.unit(),
                          v.taxIncluded(),
                          List.of())));
      var snapshot =
          new FactorPriceSnapshot(
              new FactorPriceScope("COMMERCIAL", "220", "2026-10", "BOARD", condition),
              1L,
              1,
              finance.fileName(),
              finance.fileSha256(),
              "test",
              factors,
              List.of("BOARD"),
              "test",
              null,
              null);
      for (int customerMask = 0; customerMask < 4; customerMask++) {
        BigDecimal customer304 = (customerMask & 1) == 0 ? null : new BigDecimal("23000");
        BigDecimal customer316 = (customerMask & 2) == 0 ? null : new BigDecimal("41000");
        var board = new BoardPriceInputs(customer304, customer316, Boolean.TRUE.equals(tender));
        for (String cu : List.of("90000", "100000")) {
          try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var source = workbook.getSheet("Sheet1");
            double expected304 = customer304 == null ? (Boolean.TRUE.equals(tender) ? 15 : 20) : 23;
            double expected316 = customer316 == null ? 32.5 : 41;
            double[] values = {
              expected304,
              expected316,
              expected316,
              expected304,
              cu.equals("90000") ? 90 : 100,
              21.636,
              15772.86,
              411.464
            };
            for (int i = 0; i < values.length; i++)
              source.getRow(i + 2).getCell(4).setCellValue(values[i]);
            var excel = workbook.getCreationHelper().createFormulaEvaluator();
            for (var rule : parsed.rules()) {
              var context =
                  new PriceFactorContext(
                      rule.identity().materialCode(),
                      new PriceMonthSelection("2026-10", "2026-10"),
                      snapshot,
                      cu.equals("90000") ? null : new BigDecimal(cu),
                      board);
              var cell = new CellReference(rule.cell());
              var expected =
                  excel.evaluate(
                      workbook.getSheet(rule.sheet()).getRow(cell.getRow()).getCell(cell.getCol()));
              assertThat(expected.getCellType()).isEqualTo(CellType.NUMERIC);
              var actual = calculator.calculate(rule, context);
              assertThat(actual.priceExcludingTax())
                  .as("row=%s tender=%s customer=%s Cu=%s", rule.row(), tender, customerMask, cu)
                  .isCloseTo(
                      BigDecimal.valueOf(expected.getNumberValue()),
                      within(new BigDecimal("0.00000001")));
              evidence.add(
                  Map.of(
                      "row",
                      rule.row(),
                      "material",
                      rule.identity().materialCode(),
                      "supplier",
                      rule.identity().supplierCode(),
                      "tender",
                      String.valueOf(tender),
                      "customerMask",
                      customerMask,
                      "copperPerTon",
                      cu,
                      "expected",
                      expected.getNumberValue(),
                      "actual",
                      actual.priceExcludingTax(),
                      "inputs",
                      actual.inputs()));
            }
          }
        }
      }
    }
    assertThat(evidence).hasSize(2952);
    Path report = Path.of(System.getProperty("prc.report"));
    Files.createDirectories(report.getParent());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            report.toFile(),
            Map.of(
                "passed",
                true,
                "sourceRows",
                123,
                "originalRows",
                originalEvidence,
                "grades",
                counts,
                "checks",
                evidence));
  }
}
