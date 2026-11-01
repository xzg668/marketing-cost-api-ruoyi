package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class InquiryWorkbookParserTest {
  final InquiryWorkbookParser parser = new InquiryWorkbookParser();

  static List<InquiryWorkbookParser.Factor> catalog() {
    return List.of(
        new InquiryWorkbookParser.Factor("AVG|1#CU", "上月市场1#电解铜含税平均价格", "1#Cu"),
        new InquiryWorkbookParser.Factor("AVG|1#ZN", "1#锌", "1#Zn"),
        new InquiryWorkbookParser.Factor("AVG|美国柜装黄铜", "美国柜装黄铜", "美国柜装黄铜"),
        new InquiryWorkbookParser.Factor("AVG|H65黄铜边料", "H65黄铜边料", "H65黄铜边料"));
  }

  static byte[] original() throws IOException {
    try (var stream =
        InquiryWorkbookParserTest.class.getResourceAsStream("/inquiry-pricing/采购件询价录入格式1031.xls")) {
      return Objects.requireNonNull(stream).readAllBytes();
    }
  }

  static byte[] edit(java.util.function.Consumer<Workbook> change) throws Exception {
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(original()))) {
      change.accept(book);
      var bytes = new ByteArrayOutputStream();
      book.write(bytes);
      return bytes.toByteArray();
    }
  }

  @Test
  void realXlsKeepsFixedBatchTotalAndActualLinkedFormulaWithoutReadingExternalFiles()
      throws Exception {
    var result = parser.parse(original(), "原件.xls", catalog());
    assertThat(result.rows()).hasSize(2);
    var linked = result.rows().getFirst();
    var fixed = result.rows().getLast();
    assertThat(fixed.originalPrice()).isEqualByComparingTo("0.0442");
    assertThat(fixed.materialCode()).isEmpty();
    assertThat(fixed.supplierName()).isEmpty();
    assertThat(fixed.unit()).isEmpty();
    assertThat(fixed.specification()).isEqualTo("YCQC02-043002");
    assertThat(linked.formula())
        .isEqualTo("((Y1*0.15+Y2*0.1+Y3*0.75*1.05)*1.02+0.1+L2)*K2/1000/1.13");
    assertThat(linked.originalPrice().doubleValue())
        .isCloseTo(66.24497719995264, within(0.00000001));
    assertThat(linked.factors().values())
        .containsExactlyInAnyOrder("AVG|1#CU", "AVG|1#ZN", "AVG|美国柜装黄铜");
    var updated = new LinkedHashMap<>(linked.compiled().sourceInputs());
    linked
        .factors()
        .forEach(
            (variable, key) -> {
              if (key.equals("AVG|1#CU")) updated.put(variable, new BigDecimal("100"));
            });
    assertThat(ExpressionEvaluator.evaluate(linked.compiled().expression(), updated))
        .isGreaterThan(linked.originalPrice());
  }

  @Test
  void fixedUsesMColumnEvenWhenVHasADifferentResult() throws Exception {
    var bytes = edit(book -> book.getSheetAt(0).getRow(2).getCell(21).setCellValue(999));
    assertThat(parser.parse(bytes, "固定.xls", catalog()).rows().getLast().originalPrice())
        .isEqualByComparingTo("0.0442");
  }

  @Test
  void arbitrarySheetAndMultipleSheetsAreReadByHeaders() throws Exception {
    var bytes =
        edit(
            book -> {
              book.setSheetName(0, "11月新名字");
              book.getSheetAt(0)
                  .getRow(1)
                  .getCell(21)
                  .setCellFormula("((Y1*0.15+Y2*0.1+Y3*0.75*1.05)*1.02+0.1+L2)*K2/1000/1.13");
              book.getSheetAt(0).getRow(1).getCell(21).setCellValue(66.24497719995264);
              book.cloneSheet(0);
              book.setSheetName(1, "另外一页");
            });
    var result = parser.parse(bytes, "不同文件.xls", catalog());
    assertThat(result.rows()).hasSize(4);
    assertThat(result.sheets()).containsExactly("11月新名字", "另外一页");
  }

  @Test
  void addedFactorAndMovedFactorTableUseCatalogAndActualDependencies() throws Exception {
    var factors = new ArrayList<>(catalog());
    factors.add(new InquiryWorkbookParser.Factor("AVG|新增材料", "新增材料", "新料"));
    var bytes =
        edit(
            book -> {
              var sheet = book.getSheetAt(0);
              sheet.createRow(5).createCell(26).setCellValue("新增材料");
              sheet.getRow(5).createCell(27).setCellValue(5);
              sheet.getRow(1).getCell(21).setCellFormula("AB6*2+L2");
              sheet.getRow(1).getCell(21).setCellValue(14.5);
            });
    var rule = parser.parse(bytes, "新增因素.xls", factors).rows().getFirst();
    assertThat(rule.factors().values()).containsExactly("AVG|新增材料");
    assertThat(rule.originalPrice()).isEqualByComparingTo("14.5");
  }

  @Test
  void unresolvedNumericFactorCannotBecomeAFrozenConstant() throws Exception {
    var bytes =
        edit(
            book -> {
              var sheet = book.getSheetAt(0);
              sheet.createRow(5).createCell(26).setCellValue("未入财务目录的因素");
              sheet.getRow(5).createCell(27).setCellValue(5);
              sheet.getRow(1).getCell(21).setCellFormula("AB6*2");
              sheet.getRow(1).getCell(21).setCellValue(10);
            });
    assertThatThrownBy(() -> parser.parse(bytes, "未知.xls", catalog()))
        .hasMessageContaining("影响因素无法唯一对应财务目录");
  }

  @Test
  void cachedNumberAloneCannotReplaceLinkedFormula() throws Exception {
    var bytes =
        edit(
            book -> {
              var row = book.getSheetAt(0).getRow(1);
              row.removeCell(row.getCell(21));
              row.createCell(21).setCellValue(66.24);
            });
    assertThatThrownBy(() -> parser.parse(bytes, "缺公式.xls", catalog()))
        .hasMessageContaining("必须保留实际公式");
  }

  @Test
  void badRowsRejectTheWholeFileAndPreserveExplicitZero() throws Exception {
    var bad = edit(book -> book.getSheetAt(0).getRow(2).getCell(12).setCellValue(-1));
    assertThatThrownBy(() -> parser.parse(bad, "负价.xls", catalog()))
        .hasMessageContaining("第3行")
        .hasMessageContaining("非负");
    var zero = edit(book -> book.getSheetAt(0).getRow(2).getCell(12).setCellValue(0));
    assertThat(parser.parse(zero, "零价.xls", catalog()).rows().getLast().originalPrice()).isZero();
  }

  @Test
  void xlsxSupportsLeadingZeroMaterialAndMissingUnitSupplier() throws Exception {
    for (boolean binary : List.of(false, true))
      try (Workbook book = binary ? new HSSFWorkbook() : new XSSFWorkbook()) {
        var s = book.createSheet("随便命名");
        var h = s.createRow(2);
        String[] labels = {"料号", "规格型号", "供方名称", "价格类型", "批量价总价（元）", "不含税价格"};
        for (int i = 0; i < labels.length; i++) h.createCell(i).setCellValue(labels[i]);
        var r = s.createRow(3);
        r.createCell(0).setCellValue("001230");
        r.createCell(1).setCellValue("型（A）");
        r.createCell(3).setCellValue("固定价");
        r.createCell(4).setCellValue("0.0442");
        var out = new ByteArrayOutputStream();
        book.write(out);
        var rule = parser.parse(out.toByteArray(), "询价", List.of()).rows().getFirst();
        assertThat(rule.materialCode()).isEqualTo("001230");
        assertThat(rule.originalPrice()).isEqualByComparingTo("0.0442");
      }
  }
}
