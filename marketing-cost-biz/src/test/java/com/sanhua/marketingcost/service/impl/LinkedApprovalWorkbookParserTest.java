package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;

class LinkedApprovalWorkbookParserTest {
  private final OriginalLinkedWorkbookParser parser = new OriginalLinkedWorkbookParser(
      new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
  private final OriginalLinkedWorkbookParser.ImportContext context =
      new OriginalLinkedWorkbookParser.ImportContext("210", Map.of("M1", "千克", "M2", "千克"));

  @Test
  void bothSheetsUseActualPriceFormulasAndPerRowProcessingFees() throws Exception {
    try (var book = workbook()) {
      var parsed = parse(book, context);
      assertThat(parsed.errors()).isEmpty();
      assertThat(parsed.rules()).hasSize(4);
      assertThat(parsed.rules()).extracting(OriginalLinkedWorkbookParser.Rule::cell)
          .containsExactly("J3", "J4", "X3", "X4");
      assertThat(parsed.rules()).extracting(r -> r.identity().unit())
          .containsExactly("千克", "千克", "卷", "卷");
      assertThat(parsed.rules()).allMatch(r -> !r.taxIncluded());
      assertThat(parsed.rules().getFirst().originalPrice()).isCloseTo(
          new BigDecimal("160.41338053097348"), within(new BigDecimal("0.00000001")));
      assertThat(parsed.rules().get(1).originalPrice()).isGreaterThan(parsed.rules().getFirst().originalPrice());
      assertThat(parsed.rules().getFirst().factors().values()).extracting(OriginalLinkedWorkbookParser.FactorInput::canonicalKey)
          .containsExactlyInAnyOrder("AVG|1#CU", "AVG|1#SN");
      assertThat(parsed.rules().get(2).factors().values()).extracting(OriginalLinkedWorkbookParser.FactorInput::canonicalKey)
          .containsExactly("AVG|黄金AU9999");
      assertThat(parsed.rules().get(2).factors().values()).allMatch(f -> f.sourceUnit().equals("元/克"));
      // 原稿旁的统一加工费即使改成任意数值，也不影响引用本行加工费的价格。
      book.getSheetAt(0).getRow(4).getCell(11).setCellValue(999999);
      assertThat(parse(book, context).rules()).extracting(OriginalLinkedWorkbookParser.Rule::originalPrice)
          .containsExactlyElementsOf(parsed.rules().stream().map(OriginalLinkedWorkbookParser.Rule::originalPrice).toList());
    }
  }

  @Test
  void copperTinAndGoldStayDynamicEvenBeforeFinancialCatalogExists() throws Exception {
    try (var book = workbook()) {
      var parsed = parse(book, context);
      var copper = parsed.rules().getFirst();
      Map<String, BigDecimal> values = new LinkedHashMap<>();
      copper.factors().forEach((variable, factor) -> values.put(variable,
          new BigDecimal(factor.canonicalKey().equals("AVG|1#CU") ? "100" : "500")));
      assertThat(ExpressionEvaluator.evaluate(copper.compiled().expression(), values))
          .isEqualByComparingTo(new BigDecimal("197.5").divide(new BigDecimal("1.13"), MathContext.DECIMAL128));
      var gold = parsed.rules().get(2);
      BigDecimal actual = ExpressionEvaluator.evaluate(gold.compiled().expression(),
          Map.of(gold.factors().keySet().iterator().next(), new BigDecimal("1000")));
      BigDecimal expected = new BigDecimal("1000").multiply(new BigDecimal("6.85"))
          .multiply(new BigDecimal("1.0708")).add(new BigDecimal("419.999982"))
          .divide(new BigDecimal("1.13"), MathContext.DECIMAL128);
      assertThat(actual).isCloseTo(expected, within(new BigDecimal("0.00000001")));
    }
  }

  @Test
  void organizationAndMissingUnitAreExplicitAndNeverGuessedFromOtherOrganizations() throws Exception {
    try (var book = workbook()) {
      byte[] bytes = bytes(book);
      assertThatThrownBy(() -> parser.organizations(bytes)).hasMessageContaining("组织");
      assertThat(parser.organizations(bytes, "210")).containsExactly("210");
      assertThat(parser.organizations(bytes, "220")).containsExactly("220");
      var missing = parse(book, new OriginalLinkedWorkbookParser.ImportContext("210", Map.of()));
      assertThat(missing.errors()).hasSize(2).allMatch(e -> e.message().contains("计价单位"));
      assertThat(missing.rules()).hasSize(2);
    }
  }

  @Test
  void missingPublicInputOrChangedTaxStepCannotSilentlyImportCachedPrices() throws Exception {
    try (var book = workbook()) {
      book.getSheetAt(0).getRow(3).getCell(11).setBlank();
      var missing = parse(book, context);
      assertThat(missing.errors()).hasSize(2).allMatch(e -> e.message().contains("因素原输入缺失"));
    }
    try (var book = workbook()) {
      book.getSheetAt(1).getRow(2).getCell(23).setCellFormula("Z6+I3");
      var wrongTax = parse(book, context);
      assertThat(wrongTax.errors()).hasSize(1);
      assertThat(wrongTax.errors().getFirst().message()).contains("除税");
    }
  }

  @Test
  void legacyImportDataStillOwnsMainRowsEvenWhenApprovalSheetsArePresent() throws Exception {
    try (var book = workbook()) {
      var main = book.createSheet("ImportData");
      String[] names = {"组织", "物料代码", "供应商名称", "供应商代码", "规格型号", "单位", "单价", "是否含税"};
      var header = main.createRow(0);
      for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
      var row = main.createRow(1);
      String[] values = {"220", "OLD", "供应商", "S001", "规格", "只"};
      for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
      row.createCell(6).setCellFormula("ROUND(2/3,4)");
      row.createCell(7).setCellValue(false);
      var parsed = parser.parse(bytes(book), "旧格式.xls", "COMMERCIAL", Set.of());
      assertThat(parsed.errors()).isEmpty();
      assertThat(parsed.rules()).hasSize(1);
      assertThat(parsed.rules().getFirst().identity().materialCode()).isEqualTo("OLD");
      assertThat(parsed.rules().getFirst().identity().orgCode()).isEqualTo("220");
      assertThat(parsed.rules().getFirst().originalPrice()).isEqualByComparingTo("0.6667");
    }
  }

  private OriginalLinkedWorkbookParser.ParsedWorkbook parse(Workbook book,
      OriginalLinkedWorkbookParser.ImportContext defaults) throws Exception {
    return parser.parseWithCatalog(bytes(book), "任意文件名.xls", "COMMERCIAL", org -> Set.of(), defaults);
  }

  byte[] bytes(Workbook book) throws Exception {
    book.getCreationHelper().createFormulaEvaluator().evaluateAll();
    var out = new ByteArrayOutputStream();
    book.write(out);
    return out.toByteArray();
  }

  HSSFWorkbook workbook() {
    var book = new HSSFWorkbook();
    var first = book.createSheet("任意审批名称");
    first.createRow(0).createCell(0).setCellValue("价格调整审批表");
    String[] h1 = {"物料代码4", "物料名称4", "规格4", "型号", "税率4", "现联动公式", "现不含税加工费4", "现含税加工费4", "现供方名称4"};
    var header = first.createRow(1);
    for (int i = 0; i < h1.length; i++) header.createCell(i).setCellValue(h1[i]);
    for (int i = 2; i < 4; i++) {
      var row = first.createRow(i);
      row.createCell(0).setCellValue("M" + (i - 1));
      row.createCell(1).setCellValue("焊环");
      row.createCell(2).setCellValue("规格" + i);
      row.createCell(4).setCellValue("0.13");
      row.createCell(5).setCellValue("Cu*0.915+Sn*0.08+加工费");
      row.createCell(6).setCellValue(i == 2 ? 58.407080 : 79.823097);
      row.createCell(7).setCellValue(i == 2 ? 66 : 90.2001);
      row.createCell(8).setCellValue("供应商甲");
      row.createCell(9).setCellFormula("($L$3*0.915+$L$4*0.08+H" + (i + 1) + ")/1.13");
    }
    factors(first, 10);
    var second = book.createSheet("另一张审批表");
    var h = second.createRow(1);
    Map<Integer, String> names = Map.of(0,"物料代码4",1,"物料名称4",4,"税率4",6,"现联动公式",7,"现不含税加工费4",8,"现含税加工费4",9,"现供方名称4",11,"价格单位4",23,"价格");
    names.forEach((column, name) -> h.createCell(column).setCellValue(name));
    for (int i = 2; i < 4; i++) {
      var row = second.createRow(i);
      row.createCell(0).setCellValue("G" + i);
      row.createCell(1).setCellValue("金丝");
      row.createCell(4).setCellValue("0.13");
      row.createCell(6).setCellValue(i == 2 ? "（金价*6.85克/卷）*（1+7.08%）+加工费" : "（Au（99.99）*0.492克/卷）*（1+7.08%）+加工费");
      row.createCell(7).setCellValue(i == 2 ? 371.6814 : 280.5309);
      row.createCell(8).setCellValue(i == 2 ? 419.999982 : 316.999917);
      row.createCell(9).setCellValue("供应商乙");
      row.createCell(11).setCellValue("人民币,卷");
      row.createCell(23).setCellFormula("($Z$6*" + (i == 2 ? "6.85" : "0.492") + "*(1+7.08%)+I" + (i + 1) + ")/1.13");
    }
    factors(second, 24);
    return book;
  }

  private void factors(Sheet sheet, int column) {
    String[] names = {"CU", "Sn", "加工费", "黄金AU9999"};
    double[] values = {90, 411.464, 90.2001, 938.91};
    for (int i = 0; i < names.length; i++) {
      var row = sheet.getRow(i + 2);
      if (row == null) row = sheet.createRow(i + 2);
      row.createCell(column).setCellValue(names[i]);
      row.createCell(column + 1).setCellValue(values[i]);
    }
  }
}
