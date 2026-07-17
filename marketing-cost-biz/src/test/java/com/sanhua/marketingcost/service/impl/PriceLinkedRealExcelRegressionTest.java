package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedRealExcelRegressionTest {

  private static final Path DECRYPTED_FACTOR_SAMPLE =
      Path.of("/Users/xiexicheng/Documents/sales_cost/decrypted-sheets/影响因素.xlsx");

  @Test
  @DisplayName("公式工作簿：示例联动价单价公式能解析到影响因素10 E64/E44")
  void formulaWorkbookCanParseFactorRefs() throws Exception {
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();

    List<FormulaHit> hits;
    try (InputStream input = formulaWorkbook()) {
      hits = findFormulaHits(input, parser);
    }

    assertThat(hits)
        .as("工作簿中应存在引用 影响因素10!E64 和 影响因素10!E44 的联动价公式")
        .anySatisfy(hit -> {
          assertThat(hit.formula()).contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getSheetName)
              .contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getRowNumber)
              .contains(64, 44);
        });
  }

  @Test
  @DisplayName("影响因素工作簿：能解析出 E64/E44 对应行")
  void factorRowsCanBeParsed() throws Exception {
    PriceLinkedFactorWorkbookParserImpl parser = new PriceLinkedFactorWorkbookParserImpl();

    FactorWorkbookParseResult result;
    try (InputStream input = factorWorkbook()) {
      result = parser.parse(input, "factor-fixture.xlsx");
    }

    assertThat(result.getValidRowCount()).isGreaterThan(0);
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceSheetName()).contains("影响因素");
              assertThat(row.getSourceRowNumber()).isEqualTo(64);
              assertThat(row.getFactorSeqNo()).isNotBlank();
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPriceSource()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceSheetName()).contains("影响因素");
              assertThat(row.getSourceRowNumber()).isEqualTo(44);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
  }

  @Test
  @DisplayName("联动价 sheet：扫描公式单元格并解析影响因素10 E64/E44")
  void linkedSheetFormulaCanParseFactorRefs() throws Exception {
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();

    List<FormulaHit> hits;
    try (InputStream input = formulaWorkbook()) {
      hits = findFormulaHits(input, parser);
    }

    assertThat(hits)
        .as("已解密联动价 sheet 中应存在引用 影响因素10!E64 和 影响因素10!E44 的公式")
        .anySatisfy(hit -> {
          assertThat(hit.formula()).contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getRowNumber)
              .contains(64, 44);
        });
  }

  @Test
  @DisplayName("用户提供公式文本：能解析到影响因素10 E64/E44")
  void userProvidedFormulaTextCanParseFactorRefs() {
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();
    String formula = "=ROUND($I$2*影响因素10!$E$64/1000-(I2-J2)*影响因素10!$E$44/1000+K2,4)/1.13";

    List<FormulaFactorRef> refs = parser.parse(formula);

    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).getSheetName()).isEqualTo("影响因素10");
    assertThat(refs.get(0).getColumnName()).isEqualTo("E");
    assertThat(refs.get(0).getRowNumber()).isEqualTo(64);
    assertThat(refs.get(1).getSheetName()).isEqualTo("影响因素10");
    assertThat(refs.get(1).getColumnName()).isEqualTo("E");
    assertThat(refs.get(1).getRowNumber()).isEqualTo(44);
  }

  @Test
  @DisplayName("已解密真实 sheet：影响因素 E64/E44 行能被汇总解析")
  void decryptedFactorSheetRowsCanBeParsed() throws Exception {
    Assumptions.assumeTrue(Files.exists(DECRYPTED_FACTOR_SAMPLE), "已解密影响因素 sheet 不存在");
    PriceLinkedFactorWorkbookParserImpl parser = new PriceLinkedFactorWorkbookParserImpl();

    FactorWorkbookParseResult result;
    try (InputStream input = Files.newInputStream(DECRYPTED_FACTOR_SAMPLE)) {
      result = parser.parse(input, DECRYPTED_FACTOR_SAMPLE.getFileName().toString());
    }

    assertThat(result.getValidRowCount()).isGreaterThan(0);
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceRowNumber()).isEqualTo(64);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceRowNumber()).isEqualTo(44);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
  }

  private List<FormulaHit> findFormulaHits(
      InputStream input,
      PriceLinkedFormulaFactorRefParserImpl parser)
      throws Exception {
    List<FormulaHit> hits = new ArrayList<>();
    try (Workbook workbook = WorkbookFactory.create(input)) {
      for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
        Sheet sheet = workbook.getSheetAt(s);
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
          Row row = sheet.getRow(r);
          if (row == null) {
            continue;
          }
          for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (c < 0) {
              continue;
            }
            Cell cell = row.getCell(c);
            if (cell == null || cell.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA) {
              continue;
            }
            String formula = cell.getCellFormula();
            List<FormulaFactorRef> refs = parser.parse(formula);
            if (refs.stream().anyMatch(ref -> "影响因素10".equals(ref.getSheetName())
                && Integer.valueOf(64).equals(ref.getRowNumber()))
                && refs.stream().anyMatch(ref -> "影响因素10".equals(ref.getSheetName())
                    && Integer.valueOf(44).equals(ref.getRowNumber()))) {
              hits.add(new FormulaHit(sheet.getSheetName(), r + 1, c + 1, formula, refs));
            }
          }
        }
      }
    }
    return hits;
  }

  private InputStream formulaWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet factor = workbook.createSheet("影响因素10");
      factor.createRow(43).createCell(4).setCellValue(57.119);
      factor.createRow(63).createCell(4).setCellValue(16.4);

      Sheet linked = workbook.createSheet("联动价-部品");
      Row row = linked.createRow(1);
      row.createCell(8).setCellValue(300);
      row.createCell(9).setCellValue(150);
      row.createCell(10).setCellValue(0.5);
      row.createCell(11).setCellFormula(
          "ROUND($I$2*影响因素10!$E$64/1000-(I2-J2)*影响因素10!$E$44/1000+K2,4)/1.13");
      workbook.write(output);
      return new ByteArrayInputStream(output.toByteArray());
    }
  }

  private InputStream factorWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet factor = workbook.createSheet("影响因素10");
      Row header = factor.createRow(0);
      header.createCell(0).setCellValue("序号");
      header.createCell(1).setCellValue("价表影响因素名称");
      header.createCell(2).setCellValue("简称");
      header.createCell(3).setCellValue("取价来源");
      header.createCell(4).setCellValue("价格");
      factorRow(factor.createRow(43), "44", "废料基价", "H65黄铜边料", "回收价", 57.119);
      factorRow(factor.createRow(63), "64", "不锈钢基价", "SUS304/2Bδ0.6-900", "出厂价", 16.4);
      workbook.write(output);
      return new ByteArrayInputStream(output.toByteArray());
    }
  }

  private void factorRow(
      Row row, String seq, String factorName, String shortName, String priceSource, double price) {
    row.createCell(0).setCellValue(seq);
    row.createCell(1).setCellValue(factorName);
    row.createCell(2).setCellValue(shortName);
    row.createCell(3).setCellValue(priceSource);
    row.createCell(4).setCellValue(price);
  }

  private record FormulaHit(
      String sheetName,
      int rowNumber,
      int columnNumber,
      String formula,
      List<FormulaFactorRef> refs) {
  }
}
