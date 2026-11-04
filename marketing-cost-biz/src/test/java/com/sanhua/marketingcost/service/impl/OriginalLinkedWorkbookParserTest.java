package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class OriginalLinkedWorkbookParserTest {
  private final OriginalLinkedWorkbookParser parser =
      new OriginalLinkedWorkbookParser(
          new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "（下料重量×材料含税价格－废料重量×废料价格＋加工费）/1.13\n材料含税价=Cu+7.2"
  })
  void preservesOriginalChineseTextWithoutUsingItAsTheCalculation(String description)
      throws Exception {
    try (var book = workbook("ROUND(2/3,4)")) {
      if (description != null) {
        book.getSheet("ImportData").getRow(0).createCell(8).setCellValue("联动公式");
        book.getSheet("ImportData").getRow(1).createCell(8).setCellValue(description);
      }
      var result = parse(book);
      assertThat(result.errors()).isEmpty();
      var rule = result.rules().getFirst();
      assertThat(rule.sourceFormulaText()).isEqualTo(description == null ? "" : description);
      assertThat(rule.sourceFormula()).isEqualTo("ROUND(2/3,4)");
      assertThat(rule.originalPrice()).isEqualByComparingTo("0.6667");
      assertThat(rule.identity().supplierCode()).isEqualTo("SUP");

      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var snapshot = new OriginalLinkedRuleSnapshot(json).write(java.util.List.of(rule), "input");
      var restored = json.readValue(snapshot, OriginalLinkedRuleSnapshot.SavedRule.class).rule();
      assertThat(restored.sourceFormulaText()).isEqualTo(rule.sourceFormulaText());
      // Previously published snapshots have no display text; their prices must remain readable.
      var oldSnapshot = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(snapshot);
      ((com.fasterxml.jackson.databind.node.ObjectNode) oldSnapshot.get("rule")).remove("sourceFormulaText");
      var oldRule = json.treeToValue(oldSnapshot, OriginalLinkedRuleSnapshot.SavedRule.class).rule();
      assertThat(oldRule.sourceFormulaText()).isNull();
      assertThat(oldRule.compiled()).isEqualTo(rule.compiled());
    }
  }

  @Test
  void aBlankPublicFactorNeverBecomesAnOrdinaryZeroCellEvenWhenExcelCachedAZeroPrice()
      throws Exception {
    try (var book = workbook("因素!E2")) {
      var factors = book.getSheet("因素");
      var header = factors.createRow(0);
      String[] names = {"序号", "价表影响因素名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      var row = factors.createRow(1);
      row.createCell(0).setCellValue(1);
      row.createCell(1).setCellValue("电解铜");
      row.createCell(2).setCellValue("Cu");
      row.createCell(3).setCellValue("平均价");
      row.createCell(5).setCellValue("公斤");
      var result = parse(book);
      assertThat(result.rules()).isEmpty();
      assertThat(result.errors()).hasSize(1);
      assertThat(result.errors().getFirst().message()).contains("因素原输入缺失", "因素!E2");
      row.createCell(4).setCellValue(0);
      assertThat(parse(book).errors()).isEmpty();
    }
  }

  @Test
  void ambiguousCopperInputsBlockOnlyThePricesThatReferenceThatRow() throws Exception {
    try (var book = workbook("因素!B1")) {
      var row = book.getSheet("因素").createRow(0);
      row.createCell(0).setCellValue("漆包线：铜价（元/kg）");
      row.createCell(1).setCellValue(90);
      row.createCell(2).setCellValue(100);
      var result = parse(book);
      assertThat(result.rules()).isEmpty();
      assertThat(result.errors().getFirst().message()).contains("候选数=2");
      book.getSheet("ImportData").getRow(1).getCell(6).setCellFormula("2+3");
      assertThat(parse(book).errors()).isEmpty();
    }
  }

  private OriginalLinkedWorkbookParser.ParsedWorkbook parse(XSSFWorkbook book) throws Exception {
    book.getCreationHelper().createFormulaEvaluator().evaluateAll();
    var out = new ByteArrayOutputStream();
    book.write(out);
    return parser.parse(out.toByteArray(), "原稿.xlsx", "COMMERCIAL", Set.of("AVG|1#CU"));
  }

  @Test
  void combinedMainIsAuthoritativeAndMissingPricesRemainAccountedFor() throws Exception {
    try (var book = workbook("(90+8.5)/1.13")) {
      var combined = book.cloneSheet(book.getSheetIndex("ImportData"), "合并联动价表");
      combined.getRow(1).getCell(0).setCellValue(211);
      combined.getRow(1).getCell(7).setBlank();
      book.getSheet("ImportData").getRow(1).getCell(6).setCellFormula("1/0");
      var empty = combined.createRow(2);
      empty.createCell(1).setCellValue("NO-PRICE");
      var parsed = parse(book);
      assertThat(parsed.errors()).isEmpty();
      assertThat(parsed.rules()).hasSize(1);
      assertThat(parsed.rules().getFirst().identity().orgCode()).isEqualTo("210");
      assertThat(parsed.rules().getFirst().taxIncluded()).isFalse();
      assertThat(parsed.notices()).extracting(n -> n.code())
          .containsExactlyInAnyOrder("ORGANIZATION_CORRECTED", "TAX_INFERRED", "NO_PRICE");
      combined.getRow(1).getCell(0).setCellValue(220);
      assertThat(parse(book).rules().getFirst().identity().orgCode()).isEqualTo("220");
      combined.getRow(1).getCell(6).setCellFormula("90+8.5");
      assertThat(parse(book).errors().getFirst().message()).contains("税");
    }
  }

  @Test
  void financialSchemeTableBindsActualFactorsEvenBeforeTheirCatalogIsPublished() throws Exception {
    try (var book = workbook("(因素!AB5+因素!Z6+8.5)/1.13")) {
      var factors = book.getSheet("因素");
      var header = factors.createRow(3);
      header.createCell(22).setCellValue("基价名称");
      header.createCell(23).setCellValue("基价代号");
      header.createCell(24).setCellValue("计量单位");
      header.createCell(25).setCellValue("上月均价（含税）");
      header.createCell(26).setCellValue("联动基价调整-90000元/吨");
      var copper = factors.createRow(4);
      copper.createCell(22).setCellValue("1#电解铜");
      copper.createCell(23).setCellValue("1#Cu");
      copper.createCell(24).setCellValue("元/公斤");
      copper.createCell(25).setCellValue(110);
      copper.createCell(26).setCellValue(-0.18);
      copper.createCell(27).setCellValue(90);
      var tin = factors.createRow(5);
      tin.createCell(22).setCellValue("1#锡");
      tin.createCell(23).setCellValue("1#Sn");
      tin.createCell(24).setCellValue("元/公斤");
      tin.createCell(25).setCellValue(386.721);
      factors.createRow(8).createCell(2).setCellFormula("1/0");
      var parsed = parse(book);
      assertThat(parsed.errors()).isEmpty();
      assertThat(parsed.rules().getFirst().factors().values())
          .extracting(OriginalLinkedWorkbookParser.FactorInput::canonicalKey)
          .containsExactlyInAnyOrder("AVG|1#CU", "AVG|1#SN");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void errorMaterialCellsCannotSilentlyDisappearFromTheBatch(boolean formula) throws Exception {
    try (var book = workbook("2+3")) {
      var material = book.getSheet("ImportData").getRow(1).getCell(1);
      if (formula) material.setCellFormula("1/0");
      else material.setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.REF.getCode());
      var result = parse(book);
      assertThat(result.rules()).isEmpty();
      assertThat(result.errors()).hasSize(1);
      assertThat(result.errors().getFirst().message()).contains("ImportData!B2", "料号格为错误值");
      var out = new ByteArrayOutputStream();
      book.write(out);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> parser.organizations(out.toByteArray()))
          .hasMessageContaining("料号格为错误值");
    }
  }

  @Test
  void fixedRowsInLinkedOriginalsAreExplicitErrorsInsteadOfPartialSuccess() throws Exception {
    try (var book = workbook("2+3")) {
      var sheet = book.getSheet("ImportData");
      sheet.getRow(0).createCell(8).setCellValue("订单类型");
      sheet.getRow(1).createCell(8).setCellValue("固定");
      var result = parse(book);
      assertThat(result.rules()).isEmpty();
      assertThat(result.errors()).hasSize(1);
      assertThat(result.errors().getFirst().message()).contains("固定价入口", "本批不发布任何记录");
    }
  }

  @Test
  void boardSpecificationMustAgreeWithTheActuallyReferencedGrade() throws Exception {
    try (var book = workbook("ROUND(因素!E2+1.1,4)/1.13")) {
      var main = book.getSheet("ImportData").getRow(1);
      main.getCell(0).setCellValue("220");
      var sheet = book.getSheet("因素");
      var header = sheet.createRow(0);
      String[] names = {"序号", "价表影响因素名称", "简称", "取价来源", "价格", "单位"};
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue(1);
      row.createCell(1).setCellValue("不锈钢基价");
      row.createCell(2).setCellValue("304/2B/1219");
      row.createCell(3).setCellValue("月均价");
      row.createCell(4).setCellValue(14.877);
      row.createCell(5).setCellValue("千克");
      for (String specification : new String[] {"SUS304_t1", "YL(304)-0.3-80", "S30400"}) {
        main.getCell(4).setCellValue(specification);
        var parsed = parse(book);
        assertThat(parsed.errors()).isEmpty();
        // Even a missing financial catalog entry stays a required public input, never a baked old price.
        assertThat(parsed.rules().getFirst().factors().values()).extracting(OriginalLinkedWorkbookParser.FactorInput::canonicalKey)
            .containsExactly("AVG|304/2B/1219");
      }
      for (String specification : new String[] {"", "SUS304 / SUS316L", "SUS316", "S31603", "YL(316L)"}) {
        main.getCell(4).setCellValue(specification);
        assertThat(parse(book).rules()).isEmpty();
        assertThat(parse(book).errors()).hasSize(1);
      }
      main.getCell(4).setCellValue("SUS304_t1");
      main.getCell(6).setCellFormula("14.877+1.1");
      assertThat(parse(book).errors().getFirst().message()).contains("实际因素引用不一致");
    }
  }

  private XSSFWorkbook workbook(String formula) {
    var book = new XSSFWorkbook();
    // 先建因素Sheet名称以便POI解析跨Sheet公式；测试随后补入内容。
    book.createSheet("因素");
    var sheet = book.createSheet("ImportData");
    var header = sheet.createRow(0);
    String[] names = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
    for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
    var row = sheet.createRow(1);
    String[] values = {"210", "M-01", "供应商", "SUP", "SPEC", "只"};
    for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
    row.createCell(6).setCellFormula(formula);
    row.createCell(7).setCellValue(false);
    return book;
  }
}
