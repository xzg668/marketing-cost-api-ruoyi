package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.Value;
import com.sanhua.marketingcost.service.impl.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class FinanceFactorEditWorkbookTest {
  private final FinanceFactorWorkbookParser parser = new FinanceFactorWorkbookParser(
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));

  private List<Value> original(String type) throws Exception {
    var parsed = parser.parse(FinanceFactorWorkbookParserTest.fixture(type), type + ".xlsx",
        type.equals("plate") ? "220" : "210", null);
    var distinct = new LinkedHashMap<String, Value>();
    for (var value : parsed.values()) distinct.putIfAbsent(key(value), value);
    return new ArrayList<>(distinct.values());
  }

  private byte[] export(String org, List<Value> values) {
    return FinanceFactorEditWorkbook.write("COMMERCIAL", org, "2026-10", 2,
        org.equals("220") ? "BOARD" : "CU_100000", values);
  }

  private static String key(Value v) { return v.canonicalKey() + "/" + v.schemeCode() + "/" + v.condition(); }
  private static Map<String, String> facts(List<Value> values) {
    var result = new TreeMap<String, String>();
    for (var v : values) result.put(key(v), v.price().stripTrailingZeros().toPlainString() + "/" + v.unit() + "/" + v.taxIncluded());
    return result;
  }

  @Test void allCommercialSchemesRoundTripAtFullPrecisionAndDefaultSchemeComesFirst() throws Exception {
    var values = original("commercial"); byte[] file = export("210", values);
    var parsed = parser.parse(file, "上月商用.xlsx", "210", null);
    assertThat(parsed.problems()).isEmpty();assertThat(parsed.values()).hasSize(56);
    assertThat(facts(parsed.values())).isEqualTo(facts(values));
    assertThat(parsed.schemes().getFirst().code()).isEqualTo("CU_100000");
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(file))) {
      assertThat(book.getNumberOfSheets()).isEqualTo(1);
      assertThat(book.getSheetAt(0).getSheetName()).isEqualTo("商用财务基价");
    }
  }

  @Test void boardTenderAndNonTenderPricesRoundTripWithoutRepeatingSteelConversion() throws Exception {
    var values = original("plate");
    var parsed = parser.parse(export("220", values), "上月板换.xlsx", "220", null);
    assertThat(parsed.problems()).isEmpty();assertThat(parsed.values()).hasSize(16);
    assertThat(facts(parsed.values())).isEqualTo(facts(values));
    assertThat(parsed.values()).extracting(Value::condition).contains(
        com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.TENDER,
        com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition.NON_TENDER);
  }

  @Test void adjustedCopperKeepsStableSchemeIdentityEvenIfAnotherSchemeHasSameCopperPrice() throws Exception {
    var values = original("commercial").stream().map(v -> v.canonicalKey().equals("AVG|1#CU")
        && v.schemeCode().equals("CU_90000") ? changed(v, "100") : v).toList();
    var parsed = parser.parse(export("210", values), "调整后.xlsx", "210", null);
    assertThat(parsed.problems()).isEmpty();assertThat(parsed.schemes()).hasSize(4);
    assertThat(facts(parsed.values())).isEqualTo(facts(values));
  }

  @Test void textPricesPreserveSixteenDecimalPlacesAndExplicitZeroWithoutFloatingPoint() throws Exception {
    var values = original("plate").stream().map(v -> v.canonicalKey().contains("304")
        ? changed(v, "0.0000000000000001") : changed(v, "0")).toList();
    var parsed = parser.parse(export("220", values), "精度.xlsx", "220", null);
    assertThat(parsed.problems()).isEmpty();assertThat(facts(parsed.values())).isEqualTo(facts(values));
  }

  @Test void aBoardFileCannotBeImportedAsCommercialOrViceVersa() throws Exception {
    assertThatThrownBy(() -> parser.parse(export("220", original("plate")), "板换.xlsx", "210", null)).hasMessageContaining("不能混用");
    assertThatThrownBy(() -> parser.parse(export("210", original("commercial")), "商用.xlsx", "220", null)).hasMessageContaining("不能混用");
  }

  @Test void missingNegativeAndOverPrecisePricesAreRowErrorsAndCannotBecomeZeroOrFallback() throws Exception {
    for (String value : List.of("", "-1", "0.00000000000000001", "不是价格")) {
      byte[] bytes = edit(export("220", original("plate")), book -> book.getSheetAt(0).getRow(3).getCell(5).setCellValue(value));
      var parsed = parser.parse(bytes, "错误.xlsx", "220", null);
      assertThat(parsed.problems()).anySatisfy(p -> assertThat(p.cellRef()).isEqualTo("F4"));
    }
  }

  @Test void renamedOrRemovedSchemeHeadersFailBeforeAnyPublication() throws Exception {
    byte[] file = export("210", original("commercial"));
    assertThatThrownBy(() -> parser.parse(edit(file, b -> b.getSheetAt(0).getRow(2).getCell(5).setCellValue("另一方案")), "错误.xlsx", "210", null))
        .hasMessageContaining("方案列已改变");
    assertThatThrownBy(() -> parser.parse(edit(file, b -> b.getSheetAt(0).getRow(2).removeCell(b.getSheetAt(0).getRow(2).getCell(8))), "错误.xlsx", "210", null))
        .hasMessageContaining("方案列已改变");
  }

  @Test void conflictingDuplicateFactorIsStillRejectedByTheExistingImporter() throws Exception {
    byte[] file = edit(export("220", original("plate")), b -> {
      var sheet = b.getSheetAt(0);var duplicate = sheet.createRow(sheet.getLastRowNum() + 1);
      for (var cell : sheet.getRow(3)) duplicate.createCell(cell.getColumnIndex()).setCellValue(cell.getStringCellValue());
      duplicate.getCell(5).setCellValue("999");
    });
    assertThat(parser.parse(file, "重复.xlsx", "220", null).problems()).anySatisfy(p -> assertThat(p.message()).contains("冲突"));
  }

  @Test void emptyStyledTrailingCellsDoNotMakeAnEditedExcelFileInvalid() throws Exception {
    byte[] file = edit(export("220", original("plate")), b -> {
      var sheet = b.getSheetAt(0);var style = b.createCellStyle();style.setWrapText(true);
      sheet.getRow(2).createCell(18).setCellStyle(style);sheet.getRow(3).createCell(18).setCellStyle(style);
    });
    assertThat(parser.parse(file, "格式调整.xlsx", "220", null).problems()).isEmpty();
  }

  @Test void boardCannotDropEitherConditionAndCommercialCopperMustStayPositive() throws Exception {
    byte[] board = edit(export("220", original("plate")), b -> b.getProperties().getCustomProperties()
        .getProperty("finance.base.edit.columns").setLpwstr("[{\"scheme\":\"BOARD\",\"condition\":\"NON_TENDER\"}]"));
    assertThatThrownBy(() -> parser.parse(board, "缺招标.xlsx", "220", null)).hasMessageContaining("同时保留");
    byte[] commercial = edit(export("210", original("commercial")), b -> b.getSheetAt(0).getRow(3).getCell(5).setCellValue("0"));
    assertThat(parser.parse(commercial, "零铜价.xlsx", "210", null).problems())
        .anySatisfy(p -> assertThat(p.message()).contains("Cu基价必须大于0"));
  }

  @Test void removingTheCopperRowCannotPublishAnIncompleteCommercialScheme() throws Exception {
    byte[] file = edit(export("210", original("commercial")), b -> b.getSheetAt(0).removeRow(b.getSheetAt(0).getRow(3)));
    assertThat(parser.parse(file, "缺少铜价.xlsx", "210", null).problems())
        .anySatisfy(p -> assertThat(p.message()).contains("缺少有效Cu因素"));
  }

  static Value changed(Value v, String price) {
    return new Value(v.canonicalKey(), v.factorName(), v.shortName(), v.priceSource(), v.schemeCode(),
        v.condition(), new BigDecimal(price), v.unit(), v.taxIncluded(), v.rowNumber(), v.columnNumber(),
        v.cellRef(), v.sourceValueText(), v.originalValue(), v.sourceFormula(), v.numberFormat(), v.sourceUnit(), v.conversionBasis());
  }

  private static byte[] edit(byte[] bytes, Consumer<XSSFWorkbook> change) throws Exception {
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var output = new ByteArrayOutputStream()) {
      change.accept(book);book.write(output);return output.toByteArray();
    }
  }
}
