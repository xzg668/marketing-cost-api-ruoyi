package com.sanhua.marketingcost.formula.workbook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 本地真实原稿验收，显式传入PRC-00基线；不在普通CI中假装具有业务原文件。 */
@Tag("integration")
@EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
class OriginalLinkedWorkbookCompilerAcceptanceTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final BigDecimal TOLERANCE = new BigDecimal("0.00000001");

  @Test
  void reproducesAll3776OriginalLinkedPricesWithoutUsingCachedIntermediateResults()
      throws Exception {
    Map<String, List<JsonNode>> files = new LinkedHashMap<>();
    for (var row : baseline()) {
      if (row.path("sample").asText().startsWith("L")) {
        files.computeIfAbsent(row.path("file").asText(), ignored -> new ArrayList<>()).add(row);
      }
    }
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (var file : files.entrySet()) {
      byte[] bytes = Files.readAllBytes(Path.of(file.getKey()));
      assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
          .isEqualTo(file.getValue().getFirst().path("sha256").asText());
      try (var book = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
        var compiler = new WorkbookFormulaCompiler(book, file.getKey(), Map.of());
        for (var row : file.getValue()) {
          String identity =
              row.path("sample").asText()
                  + "/"
                  + row.path("materialCode").asText()
                  + "/"
                  + row.path("row").asInt();
          try {
            var rule =
                compiler.compile(
                    new Address(row.path("sheet").asText(), row.path("priceCell").asText()));
            BigDecimal actual = ExpressionEvaluator.evaluate(rule.expression(), Map.of());
            BigDecimal expected = new BigDecimal(row.path("sourceRecalculated").asText());
            BigDecimal difference = actual.subtract(expected).abs();
            evidence.add(
                Map.of(
                    "identity",
                    identity,
                    "actual",
                    actual.toPlainString(),
                    "expected",
                    expected.toPlainString(),
                    "difference",
                    difference.toPlainString(),
                    "sourceCells",
                    rule.sources().size()));
            if (difference.compareTo(TOLERANCE) > 0) failures.add(identity + "：" + difference);
          } catch (RuntimeException error) {
            failures.add(identity + "：" + error.getMessage());
          }
        }
      }
    }
    Path report = Path.of(System.getProperty("prc.report"));
    Files.createDirectories(report.getParent());
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(report.toFile(), Map.of("rows", evidence, "failures", failures));
    assertThat(failures).isEmpty();
    assertThat(files).hasSize(10);
    assertThat(evidence).hasSize(3776);
  }

  @Test
  void wanjiaCopperChangesRecalculateTheSameSavedRuleIncludingTheActual118Multiplier()
      throws Exception {
    JsonNode target = null;
    for (var row : baseline()) {
      if (row.path("sample").asText().equals("L10")
          && row.path("materialCode").asText().equals("208200162")) target = row;
    }
    assertThat(target).isNotNull();
    try (var book =
        WorkbookFactory.create(Path.of(target.path("file").asText()).toFile(), null, true)) {
      // 该测试的源格来自逐Sheet核价证据；生产识别必须依据表结构，不按文件名/格址白名单。
      var compiler =
          new WorkbookFormulaCompiler(
              book, target.path("file").asText(), Map.of(new Address("Sheet1", "G9"), "Cu"));
      var rule = compiler.compile(new Address("ImportData", "O3"));
      assertThat(rule.expression()).contains("1.18", "[Cu]");
      assertThat(
              ExpressionEvaluator.evaluate(rule.expression(), Map.of("Cu", new BigDecimal("90"))))
          .isCloseTo(
              new BigDecimal("17.6410078611"), org.assertj.core.data.Offset.offset(TOLERANCE));
      assertThat(
              ExpressionEvaluator.evaluate(rule.expression(), Map.of("Cu", new BigDecimal("100"))))
          .isCloseTo(new BigDecimal("18.5335891"), org.assertj.core.data.Offset.offset(TOLERANCE));
      assertThat(rule.sourceInputs().get("Cu")).isEqualByComparingTo("109.114");
    }
  }

  @Test
  void all3776RulesMatchIndependentExcelEvaluationAtFinancialAndCustomerCopper() throws Exception {
    var canonical =
        new com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl(
            new com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl());
    var snapshots =
        new LinkedHashMap<String, com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot>();
    for (String org : List.of("210", "220")) {
      String resource = org.equals("210") ? "commercial" : "plate";
      String scheme = org.equals("210") ? "CU_90000" : "BOARD";
      var condition =
          org.equals("210")
              ? com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.GENERAL
              : com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.NON_TENDER;
      com.sanhua.marketingcost.dto.FinanceFactorWorkbook workbook;
      try (var input =
          getClass().getResourceAsStream("/finance-factor/" + resource + "-original.xlsx")) {
        workbook =
            new com.sanhua.marketingcost.service.financefactor.FinanceFactorWorkbookParser(
                    canonical)
                .parse(input.readAllBytes(), "财务.xlsx", org, null);
      }
      var values =
          new LinkedHashMap<
              String, com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue>();
      for (var v : workbook.values())
        if (v.schemeCode().equals(scheme) && v.condition() == condition)
          values.put(
              v.canonicalKey(),
              new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot.FactorValue(
                  1L,
                  1L,
                  v.factorName(),
                  v.shortName(),
                  v.price(),
                  v.unit(),
                  v.taxIncluded(),
                  List.of()));
      snapshots.put(
          org,
          new com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot(
              new com.sanhua.marketingcost.service.pricing.FactorPriceScope(
                  "COMMERCIAL", org, "2026-10", scheme, condition),
              1L,
              1,
              workbook.fileName(),
              workbook.fileSha256(),
              "acceptance",
              values,
              List.of(scheme),
              "test",
              null,
              null));
    }
    var parser = new com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser(canonical);
    var calculator = new com.sanhua.marketingcost.service.impl.OriginalLinkedRuleCalculator();
    var files = new java.util.LinkedHashSet<String>();
    for (var row : baseline())
      if (row.path("sample").asText().startsWith("L")) files.add(row.path("file").asText());
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (String file : files) {
      byte[] bytes = Files.readAllBytes(Path.of(file));
      var parsed =
          parser.parseWithCatalog(
              bytes, file, "COMMERCIAL", org -> snapshots.get(org).factors().keySet());
      assertThat(parsed.errors()).isEmpty();
      for (String org : parser.organizations(bytes)) {
        var snapshot = snapshots.get(org);
        var rules =
            parsed.rules().stream().filter(r -> r.identity().orgCode().equals(org)).toList();
        for (String copper : List.of("90000", "100000")) {
          try (var book = WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            var base =
                new com.sanhua.marketingcost.service.pricing.PriceFactorContext(
                    "ACCEPTANCE",
                    new com.sanhua.marketingcost.service.pricing.PriceMonthSelection(
                        "2026-10", "2026-10"),
                    snapshot,
                    copper.equals("90000") ? null : new BigDecimal(copper));
            for (var rule : rules)
              for (var factor : rule.factors().values()) {
                var address = new org.apache.poi.ss.util.CellReference(factor.address().cell());
                var cell =
                    book.getSheet(factor.address().sheet())
                        .getRow(address.getRow())
                        .getCell(address.getCol());
                cell.setBlank();
                cell.setCellValue(
                    independentFactorPrice(snapshot, factor.canonicalKey(), factor.unit(), copper)
                        .doubleValue());
              }
            var excel = book.getCreationHelper().createFormulaEvaluator();
            for (var rule : rules) {
              var context =
                  new com.sanhua.marketingcost.service.pricing.PriceFactorContext(
                      rule.identity().materialCode(),
                      base.months(),
                      snapshot,
                      base.customerCopperPricePerTon());
              var address = new org.apache.poi.ss.util.CellReference(rule.cell());
              var cell =
                  book.getSheet(rule.sheet()).getRow(address.getRow()).getCell(address.getCol());
              var evaluated = excel.evaluate(cell);
              assertThat(evaluated.getCellType()).isEqualTo(CellType.NUMERIC);
              BigDecimal expected = BigDecimal.valueOf(evaluated.getNumberValue());
              if (rule.taxIncluded())
                expected =
                    expected.divide(new BigDecimal("1.13"), java.math.MathContext.DECIMAL128);
              var calculated = calculator.calculate(rule, context);
              BigDecimal actual = calculated.priceExcludingTax();
              BigDecimal difference = actual.subtract(expected).abs();
              String key =
                  Path.of(file).getFileName()
                      + "/"
                      + rule.row()
                      + "/"
                      + rule.identity().materialCode()
                      + "/"
                      + copper;
              if (difference.compareTo(TOLERANCE) > 0) failures.add(key + "：" + difference);
              var record = new LinkedHashMap<String, Object>();
              record.put("key", key);
              record.put("file", file);
              record.put("sheet", rule.sheet());
              record.put("row", rule.row());
              record.put("material", rule.identity().materialCode());
              record.put("supplier", rule.identity().supplierCode());
              record.put("organization", org);
              record.put("formula", rule.sourceFormula());
              record.put("bindings", rule.factors());
              record.put("actualInputs", calculated.inputs());
              var independentInputs = new LinkedHashMap<String, BigDecimal>();
              rule.factors()
                  .forEach(
                      (variable, factor) ->
                          independentInputs.put(
                              variable,
                              independentFactorPrice(
                                  snapshot, factor.canonicalKey(), factor.unit(), copper)));
              record.put("independentInputs", independentInputs);
              record.put("copperPerTon", copper);
              record.put("actual", actual.toPlainString());
              record.put("expected", expected.toPlainString());
              record.put("difference", difference.toPlainString());
              evidence.add(record);
            }
          }
        }
      }
    }
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            Path.of(System.getProperty("prc.report"))
                .resolveSibling("original-finance-customer-7552.json")
                .toFile(),
            Map.of("rows", evidence, "failures", failures));
    assertThat(evidence).hasSize(7552);
    assertThat(failures).isEmpty();
  }

  /** Oracle input conversion is separate from PriceFactorContext and production unit conversion. */
  private BigDecimal independentFactorPrice(
      com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot snapshot,
      String key,
      com.sanhua.marketingcost.service.pricing.FactorPriceUnit target,
      String copperPerTon) {
    var factor = snapshot.factors().get(key);
    assertThat(factor).as("Financial source factor %s", key).isNotNull();
    BigDecimal price = key.equals("AVG|1#CU") ? new BigDecimal(copperPerTon) : factor.price();
    String sourceUnit = key.equals("AVG|1#CU") ? "CNY_TON" : factor.unit().replace('/', '_');
    var grams =
        Map.of(
            "CNY_G",
            BigDecimal.ONE,
            "CNY_KG",
            new BigDecimal("1000"),
            "CNY_TON",
            new BigDecimal("1000000"));
    return price.multiply(grams.get(target.name())).divide(grams.get(sourceUnit));
  }

  private JsonNode baseline() throws Exception {
    return JSON.readTree(Path.of(System.getProperty("prc.baseline")).toFile());
  }

  @Test
  void identifiesAllMainRowsAndPublicFactorsFromWorkbookStructure() throws Exception {
    var canonical =
        new com.sanhua.marketingcost.service.impl.FactorCanonicalKeyServiceImpl(
            new com.sanhua.marketingcost.service.impl.PriceLinkedType2TextNormalizerImpl());
    com.sanhua.marketingcost.dto.FinanceFactorWorkbook finance;
    try (var input = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      finance =
          new com.sanhua.marketingcost.service.financefactor.FinanceFactorWorkbookParser(canonical)
              .parse(input.readAllBytes(), "财务基价.xlsx", "210", null);
    }
    var keys =
        finance.values().stream()
            .map(com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value::canonicalKey)
            .collect(java.util.stream.Collectors.toSet());
    var parser = new com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser(canonical);
    Map<String, List<JsonNode>> files = new LinkedHashMap<>();
    for (var row : baseline()) {
      if (row.path("sample").asText().startsWith("L"))
        files.computeIfAbsent(row.path("file").asText(), ignored -> new ArrayList<>()).add(row);
    }
    int count = 0;
    List<String> problems = new ArrayList<>();
    List<Map<String, Object>> summary = new ArrayList<>();
    for (var file : files.entrySet()) {
      var parsed =
          parser.parse(
              Files.readAllBytes(Path.of(file.getKey())), file.getKey(), "COMMERCIAL", keys);
      parsed.errors().forEach(error -> problems.add(file.getKey() + "：" + error));
      assertThat(parsed.rules().size() + parsed.errors().size())
          .as(file.getKey())
          .isEqualTo(file.getValue().size());
      Map<Integer, JsonNode> expected = new LinkedHashMap<>();
      file.getValue().forEach(row -> expected.put(row.path("row").asInt(), row));
      for (var rule : parsed.rules()) {
        JsonNode source = expected.get(rule.row());
        assertThat(rule.identity().materialCode()).isEqualTo(source.path("materialCode").asText());
        assertThat(rule.identity().supplierCode()).isEqualTo(source.path("supplierCode").asText());
        assertThat(rule.identity().specification())
            .isEqualTo(source.path("specification").asText());
        assertThat(rule.originalPrice())
            .isCloseTo(
                new BigDecimal(source.path("sourceRecalculated").asText()),
                org.assertj.core.data.Offset.offset(TOLERANCE));
        if (source.path("sample").asText().equals("L10")) {
          assertThat(rule.factors()).as(rule.identity().materialCode()).isNotEmpty();
          assertThat(rule.factors().values()).allMatch(f -> f.canonicalKey().equals("AVG|1#CU"));
        }
        count++;
      }
      summary.add(
          Map.of(
              "file",
              file.getKey(),
              "rules",
              parsed.rules().size(),
              "errors",
              parsed.errors(),
              "factorReferences",
              parsed.rules().stream().mapToInt(r -> r.factors().size()).sum()));
    }
    JSON.writerWithDefaultPrettyPrinter()
        .writeValue(
            Path.of(System.getProperty("prc.report"))
                .resolveSibling("original-parser-3776.json")
                .toFile(),
            summary);
    assertThat(problems).isEmpty();
    assertThat(count).isEqualTo(3776);
  }
}
