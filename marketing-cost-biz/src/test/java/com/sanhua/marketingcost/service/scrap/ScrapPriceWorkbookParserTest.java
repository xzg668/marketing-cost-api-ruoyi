package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.Test;

class ScrapPriceWorkbookParserTest {
  private final ScrapPriceWorkbookParser parser = new ScrapPriceWorkbookParser(new ObjectMapper());

  static byte[] original() throws Exception {
    try (var stream =
        ScrapPriceWorkbookParserTest.class.getResourceAsStream("/scrap/original-20261017.xlsx")) {
      if (stream == null) throw new IllegalStateException("缺少废料原始验收文件");
      return stream.readAllBytes();
    }
  }

  static byte[] change(Consumer<XSSFSheet> modify) throws Exception {
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(original()));
        var output = new ByteArrayOutputStream()) {
      modify.accept((XSSFSheet) workbook.getSheet("废料清单"));
      workbook.write(output);
      return output.toByteArray();
    }
  }

  @Test
  void originalFileHasTwelveRulesOnTheNamedSheetAndNoSupplier() throws Exception {
    byte[] file = original();
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file)))
        .isEqualTo("8fd1c6ebfe206a2540ad6e2b5bef50ee4c4aef05b29eddb72a0921ccfca6471b");
    var result = parser.parse(file, "废料价格2026.10.17.xlsx");
    assertThat(result.errors()).isEmpty();
    assertThat(result.rows()).hasSize(12);
    assertThat(result.rows().stream().filter(r -> r.rule().priceType().equals("FIXED"))).hasSize(6);
    assertThat(result.rows().stream().filter(r -> r.rule().priceType().equals("LINKED")))
        .hasSize(6);
    assertThat(result.rows())
        .allSatisfy(
            row -> {
              assertThat(row.rule().supplierCode()).isNull();
              assertThat(row.rule().supplierName()).isNull();
              assertThat(row.rule().unit()).isEqualTo("KG");
              assertThat(row.evidenceJson()).contains("废料清单", "priceCell", "approvalNo");
            });
  }

  @Test
  void actualFCellsWinOverDescriptionAndReferenceSheetAndPreserveAllAmounts() throws Exception {
    var result = parser.parse(original(), "original.xlsx");
    Map<String, String> fixed =
        Map.of(
            "301990444",
            "7.566",
            "301991071",
            "17.522",
            "301990044",
            "10.177",
            "301990230",
            "172.832",
            "301990168",
            "1.673",
            "301990752",
            "2.6283185840708");
    Map<String, String> coefficients =
        Map.of(
            "301990315",
            "0.982",
            "301990218",
            "1.039",
            "301990317",
            "0.965",
            "301990135",
            "0.9845",
            "301990114",
            "0.76",
            "301991027",
            "0.982");
    var factors =
        Map.of(
            "Cu",
            new BigDecimal("90"),
            "Brass",
            new BigDecimal("52.6630228222902"),
            "Al",
            new BigDecimal("24.26"));
    for (var row : result.rows()) {
      var rule = row.rule();
      if (rule.priceType().equals("FIXED")) {
        assertThat(rule.fixedPrice()).isEqualByComparingTo(fixed.get(rule.materialCode()));
      } else {
        String variable =
            rule.materialCode().equals("301990218")
                ? "Brass"
                : rule.materialCode().equals("301990114") ? "Al" : "Cu";
        BigDecimal expected =
            factors
                .get(variable)
                .multiply(new BigDecimal(coefficients.get(rule.materialCode())))
                .divide(new BigDecimal("1.13"), MathContext.DECIMAL128);
        assertThat(ExpressionEvaluator.evaluate(rule.expression(), factors))
            .isEqualByComparingTo(expected);
      }
    }
    var enamel =
        result.rows().stream()
            .filter(r -> r.rule().materialCode().equals("301990135"))
            .findFirst()
            .orElseThrow();
    assertThat(enamel.rule().sourceFormula()).contains("0.9845");
    assertThat(enamel.evidenceJson()).contains("0.845");
  }

  @Test
  void appendedDataAndBlankFormattedTailAreHandledWithoutARowLimit() throws Exception {
    var result =
        parser.parse(
            change(
                sheet -> {
                  sheet.copyRows(1, 1, 18, new org.apache.poi.ss.usermodel.CellCopyPolicy());
                  sheet.getRow(18).getCell(0).setCellValue("NEW-SCRAP");
                  sheet.createRow(30).createCell(5).setBlank();
                }),
            "extended.xlsx");
    assertThat(result.errors()).isEmpty();
    assertThat(result.rows()).hasSize(13);
  }

  @Test
  void optionalSupplierColumnsAreNormalizedAndRemainPartOfIdentity() throws Exception {
    var result =
        parser.parse(
            change(
                sheet -> {
                  sheet.getRow(0).createCell(13).setCellValue("供应商代码");
                  sheet.getRow(0).createCell(14).setCellValue("供应商名称");
                  sheet.getRow(1).createCell(13).setCellValue(" S1 ");
                  sheet.getRow(1).createCell(14).setCellValue(" 供方 ");
                  sheet.copyRows(1, 1, 13, new CellCopyPolicy());
                  sheet.getRow(13).getCell(13).setBlank();
                }),
            "supplier.xlsx");
    assertThat(result.errors()).isEmpty();
    assertThat(result.rows()).hasSize(13);
    assertThat(result.rows().getFirst().rule().supplierCode()).isEqualTo("S1");
    assertThat(result.rows().getFirst().rule().supplierName()).isEqualTo("供方");
    assertThat(result.rows().getLast().rule().supplierCode()).isNull();
  }

  @Test
  void identicalDuplicateMergesAndConflictingDuplicateIsAnError() throws Exception {
    var identical =
        parser.parse(change(s -> s.copyRows(1, 1, 13, new CellCopyPolicy())), "same.xlsx");
    assertThat(identical.rows()).hasSize(12);
    assertThat(identical.duplicates()).isEqualTo(1);
    assertThat(identical.errors()).isEmpty();
    var conflict =
        parser.parse(
            change(
                s -> {
                  s.copyRows(1, 1, 13, new CellCopyPolicy());
                  s.getRow(13).getCell(5).setCellValue(8);
                }),
            "conflict.xlsx");
    assertThat(conflict.errors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.row()).isEqualTo(14);
              assertThat(error.message()).contains("第2行", "内容不同");
            });
  }

  @Test
  void linkedCacheIsNeverAcceptedAsARule() throws Exception {
    var result =
        parser.parse(
            change(s -> s.getRow(3).getCell(5).setCellFormula(null)), "missing-formula.xlsx");
    assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("实际Excel公式"));
  }

  @Test
  void targetDependencyErrorsAndCyclesAreReportedAtTheSourceRow() throws Exception {
    for (String formula :
        new String[] {"F4", "UnknownFactor*0.982/1.13", "#REF!*0.982/1.13", "M1/(1-1)"}) {
      var result =
          parser.parse(
              change(
                  s -> {
                    var unknown = s.getWorkbook().createName();
                    unknown.setNameName("UnknownFactor");
                    unknown.setRefersToFormula("'废料清单'!$M$1");
                    s.getRow(3).getCell(5).setCellFormula(formula);
                  }),
              "invalid.xlsx");
      assertThat(result.errors()).as(formula).anySatisfy(e -> assertThat(e.row()).isEqualTo(4));
    }
  }

  @Test
  void missingHeaderWrongUnitNegativeFixedPriceAndUnknownFactorFailExplicitly() throws Exception {
    assertThatThrownBy(
            () -> parser.parse(change(s -> s.getRow(0).getCell(5).setCellValue("金额")), "bad.xlsx"))
        .hasMessageContaining("表头");
    var result =
        parser.parse(
            change(
                s -> {
                  s.getRow(1).getCell(3).setCellValue("吨");
                  s.getRow(2).getCell(5).setCellValue(-1);
                  s.getRow(0).getCell(11).setCellValue("未识别基价");
                }),
            "bad.xlsx");
    assertThat(result.errors()).hasSize(6); // 单位、负数，以及四条失去铜绑定的公式。
  }
}
