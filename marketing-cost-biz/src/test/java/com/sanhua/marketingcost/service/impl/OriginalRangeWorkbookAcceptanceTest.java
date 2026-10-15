package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("integration")
@EnabledIfSystemProperty(named = "prc.baseline", matches = ".+")
class OriginalRangeWorkbookAcceptanceTest {
  @Test
  void all378MainPricesBindToTheirOwnSupplierAndActualFinancialBand() throws Exception {
    var json = new ObjectMapper();
    Map<String, List<JsonNode>> files = new LinkedHashMap<>();
    for (var row : json.readTree(Path.of(System.getProperty("prc.baseline")).toFile()))
      if (row.path("sample").asText().startsWith("R"))
        files.computeIfAbsent(row.path("file").asText(), ignored -> new ArrayList<>()).add(row);
    var parser = new OriginalRangeWorkbookParser();
    var calculator = new OriginalRangeRuleCalculator();
    List<String> failures = new ArrayList<>();
    List<Map<String, Object>> evidence = new ArrayList<>();
    List<Map<String, Object>> boundaryCases = new ArrayList<>();
    for (var entry : files.entrySet()) {
      byte[] bytes = Files.readAllBytes(Path.of(entry.getKey()));
      assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
          .isEqualTo(entry.getValue().getFirst().path("sha256").asText());
      var parsed = parser.parse(bytes, entry.getKey(), "COMMERCIAL");
      parsed.errors().forEach(error -> failures.add(entry.getKey() + " / " + error));
      for (var expected : entry.getValue()) {
        String key = expected.path("sample").asText() + "/" + expected.path("row").asInt();
        try {
          var rules =
              parsed.rules().stream().filter(r -> r.row() == expected.path("row").asInt()).toList();
          if (rules.size() != 1) throw new IllegalArgumentException("解析规则数量=" + rules.size());
          var rule = rules.getFirst();
          BigDecimal factor = new BigDecimal(expected.path("ruleSource").path("target").asText());
          if (rule.factorKey().equals("AVG|1#CU"))
            factor =
                com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_TON.convert(
                    factor, rule.factorUnit());
          for (String copper : List.of("90000", "90000.5", "90001", "94999", "95000", "100000")) {
            var test = new LinkedHashMap<String, Object>();
            test.put("identity", key); test.put("copperPerTon", copper);
            BigDecimal target = rule.factorKey().equals("AVG|1#CU")
                ? com.sanhua.marketingcost.service.pricing.FactorPriceUnit.CNY_TON.convert(new BigDecimal(copper), rule.factorUnit())
                : factor;
            test.put("factorValue", target);
            try {
              var selected = calculator.select(rule, target);
              test.put("source", selected.sourceBand().address().sheet() + "!" + selected.sourceBand().address().cell());
              test.put("price", selected.priceExcludingTax());
              test.put("bounds", selected.selectedBounds());
            } catch (IllegalArgumentException error) { test.put("error", error.getMessage()); }
            boundaryCases.add(test);
          }
          var result = calculator.select(rule, factor);
          String source =
              result.sourceBand().address().sheet() + "!" + result.sourceBand().address().cell();
          BigDecimal price = new BigDecimal(expected.path("sourceRecalculated").asText());
          BigDecimal diff = result.priceExcludingTax().subtract(price).abs();
          if (!source.equals(expected.path("selectedSource").asText()))
            throw new IllegalArgumentException(
                "矩阵串行：" + source + " / " + expected.path("selectedSource"));
          if (diff.compareTo(new BigDecimal("0.00000001")) > 0)
            throw new IllegalArgumentException("单价差=" + diff);
          if (!rule.identity().supplierCode().equals(expected.path("supplierCode").asText()))
            throw new IllegalArgumentException("供应商身份变化");
          evidence.add(
              Map.of(
                  "identity",
                  key,
                  "material",
                  rule.identity().materialCode(),
                  "supplier",
                  rule.identity().supplierCode(),
                  "source",
                  source,
                  "factor",
                  factor,
                  "price",
                  result.priceExcludingTax(),
                  "expected",
                  price,
                  "difference",
                  diff,
                  "bands",
                  rule.bands().size(),
                  "extrapolation",
                  calculator.extrapolation(rule) != null));
        } catch (RuntimeException error) {
          failures.add(key + "：" + error.getMessage());
        }
      }
    }
    Path report = Path.of(System.getProperty("prc.report"));
    Files.createDirectories(report.getParent());
    json.writerWithDefaultPrettyPrinter()
        .writeValue(report.toFile(), Map.of("rows", evidence, "boundaryCases", boundaryCases, "failures", failures));
    assertThat(failures).isEmpty();
    assertThat(files).hasSize(11);
    assertThat(evidence).hasSize(378);
  }
}
