package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.technicaldata.TechnicalBomRows.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class TechnicalBomWorkbookTest {
  private final TechnicalBomWorkbook workbook = new TechnicalBomWorkbook();
  private final Product sample = new Product("001000", "已有成品", "EXAMPLE", "规格");
  private Row row(int line, String level, String code, String qty) {
    return new Row(line, level, code, "子件", "", "", new BigDecimal(qty), "只", "采购件", "铜", null, "");
  }
  private byte[] filled() throws Exception {
    byte[] template = workbook.template(sample, List.of(row(2, "1", "000123", "2"), row(3, "1.10", "000456", "3")));
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(template)); var out = new ByteArrayOutputStream()) {
      var entry = book.getSheet(TechnicalBomWorkbook.ENTRY);
      var original = book.getSheet(TechnicalBomWorkbook.EXAMPLE);
      for (int index = 1; index <= 2; index++) {
        var added = entry.createRow(index);
        for (int col = 0; col < HEADERS.size(); col++) added.createCell(col, CellType.STRING).setCellValue(original.getRow(index).getCell(col).getStringCellValue());
        added.getCell(0).setCellValue(""); added.getCell(2).setCellValue("NEW-MODEL");
      }
      book.write(out); return out.toByteArray();
    }
  }
  private byte[] mutate(byte[] bytes, java.util.function.Consumer<org.apache.poi.ss.usermodel.Workbook> mutation) throws Exception {
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes)); var out = new ByteArrayOutputStream()) {
      mutation.accept(book); book.write(out); return out.toByteArray();
    }
  }
  @Test void blankEntryNeverImportsTheExample() throws Exception {
    var template = workbook.template(sample, List.of(row(2, "1", "001", "1")));
    var parsed = workbook.parse("BOM.xlsx", template);
    assertThat(parsed.rows()).isEmpty(); assertThat(parsed.issues()).isNotEmpty();
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(template))) {
      assertThat(book.getNumberOfSheets()).isEqualTo(2);
      assertThat(book.getSheet("BOM填写").getLastRowNum()).isZero();
      assertThat(book.getSheet("BOM填写").getColumnStyle(4).getDataFormatString()).isEqualTo("@");
      assertThat(book.getSheet("BOM填写").getRow(0).getCell(14).getStringCellValue()).isEqualTo("子件净重单位");
    }
  }
  @Test void ownProductWithoutCodeAndTextHierarchyAndLeadingZeroSurviveRoundtrip() throws Exception {
    var parsed = workbook.parse("BOM.xlsx", filled());
    assertThat(parsed.issues()).isEmpty(); assertThat(parsed.product().code()).isEmpty();
    assertThat(parsed.product().model()).isEqualTo("NEW-MODEL");
    assertThat(parsed.rows()).extracting(Row::hierarchy).containsExactly("1", "1.10");
    assertThat(parsed.rows()).extracting(Row::materialCode).containsExactly("000123", "000456");
    assertThat(cumulativeQuantities(parsed.rows()).get("1.10")).isEqualByComparingTo("6");
  }
  @Test void numericHierarchyIsRejectedBeforeItCanCollapseOnePointTen() throws Exception {
    var bytes = mutate(filled(), book -> book.getSheet("BOM填写").getRow(2).getCell(4).setCellValue(1.10));
    assertThat(workbook.parse("BOM.xlsx", bytes).issues()).anySatisfy(issue -> {
      assertThat(issue.lineNo()).isEqualTo(3); assertThat(issue.message()).contains("层级编号须按文本");
    });
  }
  @Test void missingParentAndDuplicateNodeAreSpecificButRepeatedMaterialIsAllowed() {
    var repeated = List.of(row(2, "1", "001", "2"), row(3, "2", "001", "4"), row(4, "2.10", "001", "3"));
    assertThat(validate(sample, repeated)).isEmpty();
    assertThat(cumulativeQuantities(repeated).get("2.10")).isEqualByComparingTo("12");
    assertThat(validate(sample, List.of(row(2, "1.10", "001", "1")))).anySatisfy(issue -> assertThat(issue.message()).contains("缺少直接父节点：1"));
    assertThat(validate(sample, List.of(row(2, "1", "001", "1"), row(3, "1", "002", "1")))).anySatisfy(issue -> assertThat(issue.message()).contains("重复"));
  }
  @Test void orderDoesNotChangeDirectParentMultiplicationAndGapsAreLegal() {
    var rows = List.of(row(2, "2.10.3", "001", "0.5"), row(3, "2", "002", "4"), row(4, "2.10", "003", "3"));
    assertThat(validate(sample, rows)).isEmpty();
    assertThat(cumulativeQuantities(rows).get("2.10.3")).isEqualByComparingTo("6");
  }
  @Test void exampleEditsAreIgnoredButEntryFormulasAreRejected() throws Exception {
    var modified = mutate(filled(), book -> book.getSheet("填写示例").getRow(1).getCell(9).setCellFormula("1+1"));
    assertThat(workbook.parse("BOM.xlsx", modified).issues()).isEmpty();
    modified = mutate(modified, book -> book.getSheet("BOM填写").getRow(1).getCell(9).setCellFormula("1+1"));
    assertThat(workbook.parse("BOM.xlsx", modified).issues()).anySatisfy(issue -> assertThat(issue.message()).contains("不使用公式"));
  }
  @Test void mixedProductIdentityCannotSilentlyBindAnotherProduct() throws Exception {
    var bytes = mutate(filled(), book -> book.getSheet("BOM填写").getRow(2).getCell(2).setCellValue("OTHER-MODEL"));
    assertThat(workbook.parse("BOM.xlsx", bytes).issues()).anySatisfy(issue -> assertThat(issue.message()).contains("一次只上传当前产品"));
  }
  @Test void absentWeightStaysAbsentAndZeroWeightIsExplicit() {
    var missing = row(2, "1", "001", "1");
    assertThat(validate(sample, List.of(missing))).isEmpty(); assertThat(missing.netWeight()).isNull();
    var zero = new Row(2, "1", "001", "子件", "", "", BigDecimal.ONE, "只", "采购件", "铜", BigDecimal.ZERO, "g");
    assertThat(validate(sample, List.of(zero))).isEmpty();
    var noUnit = new Row(2, "1", "001", "子件", "", "", BigDecimal.ONE, "只", "采购件", "铜", BigDecimal.ONE, "");
    assertThat(validate(sample, List.of(noUnit))).anySatisfy(issue -> assertThat(issue.field()).isEqualTo("子件净重单位"));
  }
}
