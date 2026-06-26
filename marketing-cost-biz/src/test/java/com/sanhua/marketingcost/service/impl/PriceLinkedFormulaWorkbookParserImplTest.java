package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.LinkedFormulaRow;
import com.sanhua.marketingcost.dto.LinkedFormulaSheetParseResult;
import com.sanhua.marketingcost.dto.LinkedFormulaWorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedFormulaWorkbookParserImplTest {

  private final PriceLinkedFormulaWorkbookParserImpl parser =
      new PriceLinkedFormulaWorkbookParserImpl();

  @Test
  @DisplayName("parse：不依赖 sheet 顺序，能定位联动公式 sheet 和单价列")
  void parseRecognizesLinkedFormulaSheetByHeaders() throws Exception {
    byte[] workbook = buildWorkbook(true, true, false);

    LinkedFormulaWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx");

    assertThat(result.getSourceFileName()).isEqualTo("monthly.xlsx");
    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getSheets()).hasSize(1);
    LinkedFormulaSheetParseResult sheet = result.getSheets().getFirst();
    assertThat(sheet.getSheetName()).isEqualTo("联动价-部品6");
    assertThat(sheet.getHeaderRowNumber()).isEqualTo(2);
    assertThat(sheet.getRows()).hasSize(1);
  }

  @Test
  @DisplayName("parse：能读取单价单元格真实公式并保留 Excel 行号")
  void parseReadsPriceCellFormulaAndExcelRowNumber() throws Exception {
    byte[] workbook = buildWorkbook(true, true, false);

    LinkedFormulaRow row = parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx")
        .getSheets().getFirst().getRows().getFirst();

    assertThat(row.getSourceSheetName()).isEqualTo("联动价-部品6");
    assertThat(row.getExcelRowNumber()).isEqualTo(3);
    assertThat(row.getMaterialCode()).isEqualTo("MAT-1001");
    assertThat(row.getLinkedItemImportKey()).isEqualTo("MAT-1001|S0001|SHF-001");
    assertThat(row.getFormulaText()).isEqualTo("材料含税价格*下料重量+加工费");
    assertThat(row.getPriceCellFormula())
        .isEqualTo("ROUND($I$2*影响因素!$E$64/1000+K3,4)/1.13");
    assertThat(row.getExcelDerivedFormulaText())
        .isEqualTo("($I$2*影响因素!$E$64/1000+K3)/1.13");
    assertThat(row.getHasFormula()).isTrue();
  }

  @Test
  @DisplayName("parse：单价原始公式能转换本行重量和加工费单元格为系统字段 token")
  void parseDerivesSystemFormulaTextFromExcelFormula() throws Exception {
    byte[] workbook;
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("联动公式");
      Row header = sheet.createRow(0);
      String[] headers = {
          "物料代码", "供应商代码", "规格型号", "联动公式", "下料重", "净重", "加工费", "单价"
      };
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue("MAT-1001");
      row.createCell(1).setCellValue("S0001");
      row.createCell(2).setCellValue("SHF-001");
      row.createCell(3).setCellValue("下料重量*材料含税价格");
      row.createCell(4).setCellValue(30);
      row.createCell(5).setCellValue(20);
      row.createCell(6).setCellValue(12);
      row.createCell(7).setCellFormula(
          "ROUND(E2*影响因素10!$E$64/1000-(E2-F2)*影响因素10!$E$44/1000+G2,4)/1.13");
      wb.write(out);
      workbook = out.toByteArray();
    }

    LinkedFormulaRow row = parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx")
        .getSheets().getFirst().getRows().getFirst();

    assertThat(row.getExcelDerivedFormulaText())
        .isEqualTo("([blank_weight]*影响因素10!$E$64/1000-([blank_weight]-[net_weight])"
            + "*影响因素10!$E$44/1000+[process_fee])/1.13");
  }

  @Test
  @DisplayName("parse：能读取单价公式计算值作为 Excel 金标")
  void parseReadsFormulaPriceValueAsGoldenPrice() throws Exception {
    byte[] workbook;
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet factor = wb.createSheet("影响因素10");
      Row factorHeader = factor.createRow(0);
      factorHeader.createCell(0).setCellValue("序号");
      factorHeader.createCell(1).setCellValue("价表影响因素名称");
      factorHeader.createCell(2).setCellValue("简称");
      factorHeader.createCell(3).setCellValue("取价来源");
      factorHeader.createCell(4).setCellValue("价格");
      Row factorRow = factor.createRow(63);
      factorRow.createCell(4).setCellValue(16.4);

      Sheet linked = wb.createSheet("联动公式");
      Row header = linked.createRow(0);
      String[] headers = {"物料代码", "供应商代码", "规格型号", "联动公式", "下料重", "单价"};
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }
      Row row = linked.createRow(1);
      row.createCell(0).setCellValue("MAT-1001");
      row.createCell(1).setCellValue("S0001");
      row.createCell(2).setCellValue("SHF-001");
      row.createCell(3).setCellValue("下料重量*材料含税价格");
      row.createCell(4).setCellValue(30);
      row.createCell(5).setCellFormula("E2*影响因素10!$E$64/1000");
      wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
      wb.write(out);
      workbook = out.toByteArray();
    }

    LinkedFormulaRow row = parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx")
        .getSheets().getFirst().getRows().getFirst();

    assertThat(row.getPriceCellValue()).isEqualByComparingTo("0.492");
  }

  @Test
  @DisplayName("parse：单价列是纯值时不阻断解析，标记为无法自动绑定")
  void parseKeepsValuePriceRowButMarksNoFormula() throws Exception {
    byte[] workbook = buildWorkbook(true, false, false);

    LinkedFormulaRow row = parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx")
        .getSheets().getFirst().getRows().getFirst();

    assertThat(row.getMaterialCode()).isEqualTo("MAT-1001");
    assertThat(row.getPriceCellFormula()).isNull();
    assertThat(row.getHasFormula()).isFalse();
  }

  @Test
  @DisplayName("parse：联动固定混合 sheet 中固定行不进入联动公式解析结果")
  void parseSkipsFixedRowsWhenOrderTypeExists() throws Exception {
    byte[] workbook = buildWorkbook(true, true, true);

    LinkedFormulaSheetParseResult sheet = parser.parse(
        new ByteArrayInputStream(workbook), "monthly.xlsx").getSheets().getFirst();

    assertThat(sheet.getRows()).hasSize(1);
    assertThat(sheet.getRows().getFirst().getMaterialCode()).isEqualTo("MAT-1001");
  }

  @Test
  @DisplayName("parse：影响因素 sheet 不会被误识别为联动公式 sheet")
  void parseIgnoresFactorOnlyWorkbook() throws Exception {
    byte[] workbook;
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      createFactorSheet(wb);
      wb.write(out);
      workbook = out.toByteArray();
    }

    LinkedFormulaWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "factor.xlsx");

    assertThat(result.getSheets()).isEmpty();
    assertThat(result.getRowCount()).isZero();
  }

  @Test
  @DisplayName("parse：隐藏联动公式 sheet 不参与解析")
  void parseSkipsHiddenLinkedFormulaSheets() throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      createLinkedFormulaSheet(wb, "隐藏联动公式", "MAT-HIDDEN");
      wb.setSheetHidden(0, true);
      createLinkedFormulaSheet(wb, "联动价-部品6", "MAT-VISIBLE");
      wb.write(out);

      LinkedFormulaWorkbookParseResult result =
          parser.parse(new ByteArrayInputStream(out.toByteArray()), "monthly.xlsx");

      assertThat(result.getSheets())
          .extracting(LinkedFormulaSheetParseResult::getSheetName)
          .containsExactly("联动价-部品6");
      LinkedFormulaSheetParseResult sheet = result.getSheets().getFirst();
      assertThat(sheet.getRows()).hasSize(1);
      assertThat(sheet.getRows().getFirst().getMaterialCode()).isEqualTo("MAT-VISIBLE");
    }
  }

  @Test
  @DisplayName("parse：用户 demo4 联动价 xls 只解析可见联动公式 sheet")
  void parseUserDemo4LinkedWorkbookIgnoresHiddenFormulaSheets() throws Exception {
    Path sample = Path.of("/Users/xiexicheng/Desktop/demo4/联动价.xls");
    Assumptions.assumeTrue(Files.exists(sample), "用户 demo4 联动价 xls 不存在，跳过本地回归");

    try (InputStream input = Files.newInputStream(sample)) {
      LinkedFormulaWorkbookParseResult result =
          parser.parse(input, sample.getFileName().toString());

      assertThat(result.getErrors()).isEmpty();
      assertThat(result.getSheets())
          .extracting(LinkedFormulaSheetParseResult::getSheetName)
          .containsExactly("联动价-部品6");
      LinkedFormulaSheetParseResult sheet = result.getSheets().getFirst();
      assertThat(sheet.getRows()).hasSize(12);
      assertThat(sheet.getRows())
          .extracting(LinkedFormulaRow::getExcelRowNumber)
          .containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14);
      assertThat(sheet.getRows()).allSatisfy(row -> assertThat(row.getHasFormula()).isTrue());
    }
  }

  private byte[] buildWorkbook(boolean includeFactorSheet, boolean formulaPrice, boolean fixedRow)
      throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (includeFactorSheet) {
        createFactorSheet(wb);
      }
      Sheet sheet = wb.createSheet("联动价-部品6");
      sheet.createRow(0).createCell(0).setCellValue("2026 年 5 月联动价");
      Row header = sheet.createRow(1);
      String[] headers = {
          "组织", "供应商代码", "物料代码", "规格型号", "联动公式", "单价", "订单类型"
      };
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }

      Row linked = sheet.createRow(2);
      linked.createCell(0).setCellValue("210");
      linked.createCell(1).setCellValue("S0001");
      linked.createCell(2).setCellValue("MAT-1001");
      linked.createCell(3).setCellValue("SHF-001");
      linked.createCell(4).setCellValue("材料含税价格*下料重量+加工费");
      if (formulaPrice) {
        linked.createCell(5).setCellFormula("ROUND($I$2*影响因素!$E$64/1000+K3,4)/1.13");
      } else {
        linked.createCell(5).setCellValue(88.8);
      }
      linked.createCell(6).setCellValue("联动");

      if (fixedRow) {
        Row fixed = sheet.createRow(3);
        fixed.createCell(1).setCellValue("S0002");
        fixed.createCell(2).setCellValue("MAT-FIXED");
        fixed.createCell(3).setCellValue("FIX-001");
        fixed.createCell(5).setCellValue(7.0);
        fixed.createCell(6).setCellValue("固定");
      }

      wb.write(out);
      return out.toByteArray();
    }
  }

  private void createFactorSheet(XSSFWorkbook wb) {
    Sheet sheet = wb.createSheet("影响因素");
    Row header = sheet.createRow(0);
    header.createCell(0).setCellValue("序号");
    header.createCell(1).setCellValue("价表影响因素名称");
    header.createCell(2).setCellValue("简称");
    header.createCell(3).setCellValue("取价来源");
    header.createCell(4).setCellValue("价格");
    Row row = sheet.createRow(63);
    row.createCell(0).setCellValue(64);
    row.createCell(1).setCellValue("上月宁波宝新SUS304S/Bδ0.6出厂价-900元");
    row.createCell(2).setCellValue("SUS304/2Bδ0.6-900");
    row.createCell(3).setCellValue("出厂价");
    row.createCell(4).setCellValue(16.4);
  }

  private void createLinkedFormulaSheet(
      XSSFWorkbook wb, String sheetName, String materialCode) {
    Sheet sheet = wb.createSheet(sheetName);
    Row header = sheet.createRow(0);
    String[] headers = {
        "组织", "供应商代码", "物料代码", "规格型号", "联动公式", "单价", "订单类型"
    };
    for (int i = 0; i < headers.length; i++) {
      header.createCell(i).setCellValue(headers[i]);
    }
    Row row = sheet.createRow(1);
    row.createCell(0).setCellValue("210");
    row.createCell(1).setCellValue("S0001");
    row.createCell(2).setCellValue(materialCode);
    row.createCell(3).setCellValue("SPEC-A");
    row.createCell(4).setCellValue("材料含税价格*下料重量+加工费");
    row.createCell(5).setCellFormula("ROUND($I$2*影响因素!$E$64/1000+K2,4)/1.13");
    row.createCell(6).setCellValue("联动");
  }
}
