package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PriceLinkedFactorWorkbookParserImplTest {

  private final PriceLinkedFactorWorkbookParserImpl parser =
      new PriceLinkedFactorWorkbookParserImpl();

  @Test
  @DisplayName("parse：不依赖 sheet 名，只靠影响因素表头识别")
  void parseRecognizesFactorSheetByHeadersWithoutSheetName() throws Exception {
    byte[] workbook = buildWorkbook("财务报价基准", true, 1, false);

    FactorWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx");

    assertThat(result.getSourceFileName()).isEqualTo("monthly.xlsx");
    assertThat(result.getSheets()).hasSize(1);
    FactorSheetParseResult sheet = result.getSheets().getFirst();
    assertThat(sheet.getSheetName()).isEqualTo("财务报价基准");
    assertThat(sheet.getHeaderRowNumber()).isEqualTo(2);
    assertThat(sheet.getErrors()).isEmpty();
    assertThat(sheet.getRows()).hasSize(1);

    FactorRowParseResult row = sheet.getRows().getFirst();
    assertThat(row.getSourceSheetName()).isEqualTo("财务报价基准");
    assertThat(row.getSourceRowNumber()).isEqualTo(3);
    assertThat(row.getFactorSeqNo()).isEqualTo("64");
    assertThat(row.getFactorName()).isEqualTo("上月宁波宝新SUS304S/Bδ0.6出厂价-900元");
    assertThat(row.getShortName()).isEqualTo("SUS304/2Bδ0.6-900");
    assertThat(row.getPriceSource()).isEqualTo("出厂价");
    assertThat(row.getPrice()).isEqualByComparingTo(new BigDecimal("16.4"));
    assertThat(row.getUnit()).isEqualTo("公斤");
    assertThat(row.getOriginalPrice()).isEqualByComparingTo(new BigDecimal("16.2"));
  }

  @Test
  @DisplayName("parse：保留 Excel 原始行号，例如第 64 行")
  void parseKeepsOriginalExcelRowNumber() throws Exception {
    byte[] workbook = buildWorkbook("任意名称", true, 62, false);

    FactorWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx");

    assertThat(result.getSheets()).hasSize(1);
    assertThat(result.getSheets().getFirst().getRows()).hasSize(1);
    assertThat(result.getSheets().getFirst().getRows().getFirst().getSourceRowNumber())
        .isEqualTo(64);
  }

  @Test
  @DisplayName("parse：缺少序号表头时返回明确错误，不解析数据行")
  void parseReportsErrorWhenSeqHeaderMissing() throws Exception {
    byte[] workbook = buildWorkbook("影响因素但缺序号", false, 1, false);

    FactorWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx");

    assertThat(result.getSheets()).hasSize(1);
    FactorSheetParseResult sheet = result.getSheets().getFirst();
    assertThat(sheet.getRows()).isEmpty();
    assertThat(sheet.getErrors()).hasSize(1);
    assertThat(sheet.getErrors().getFirst().getMessage()).contains("序号");
  }

  @Test
  @DisplayName("parse：行序号为空时跳过该行并返回错误")
  void parseSkipsRowWithoutSeq() throws Exception {
    byte[] workbook = buildWorkbook("影响因素", true, 1, true);

    FactorWorkbookParseResult result =
        parser.parse(new ByteArrayInputStream(workbook), "monthly.xlsx");

    FactorSheetParseResult sheet = result.getSheets().getFirst();
    assertThat(sheet.getRows()).isEmpty();
    assertThat(sheet.getErrors()).hasSize(1);
    assertThat(sheet.getErrors().getFirst().getRowNumber()).isEqualTo(3);
    assertThat(sheet.getErrors().getFirst().getMessage()).contains("缺少序号");
  }

  @Test
  @DisplayName("parse：联动公式 sheet 不会被误识别为影响因素 sheet")
  void parseIgnoresLinkedFormulaSheet() throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet linked = wb.createSheet("联动公式");
      Row header = linked.createRow(0);
      header.createCell(0).setCellValue("物料代码");
      header.createCell(1).setCellValue("联动公式");
      header.createCell(2).setCellValue("单价");
      wb.write(out);

      FactorWorkbookParseResult result =
          parser.parse(new ByteArrayInputStream(out.toByteArray()), "monthly.xlsx");

      assertThat(result.getSheets()).isEmpty();
    }
  }

  @Test
  @DisplayName("parse：当前样例影响因素 Excel 能解析出有效行")
  void parseRealDecryptedFactorWorkbook() throws Exception {
    Path sample = findSampleFactorWorkbook();
    Assumptions.assumeTrue(Files.exists(sample), "样例影响因素 Excel 不存在，跳过本地样例回归");

    try (InputStream input = Files.newInputStream(sample)) {
      FactorWorkbookParseResult result = parser.parse(input, sample.getFileName().toString());

      assertThat(result.getSheets()).isNotEmpty();
      assertThat(result.getValidRowCount()).isGreaterThan(0);
      assertThat(result.getSheets())
          .anySatisfy(sheet -> assertThat(sheet.getRows())
              .anySatisfy(row -> {
                assertThat(row.getSourceRowNumber()).isEqualTo(64);
                assertThat(row.getShortName()).contains("SUS304");
                assertThat(row.getPriceSource()).isNotBlank();
                assertThat(row.getPrice()).isNotNull();
              }));
    }
  }

  private byte[] buildWorkbook(
      String sheetName, boolean includeSeqHeader, int dataRowIndex, boolean blankSeq)
      throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet(sheetName);
      sheet.createRow(0).createCell(0).setCellValue("2026年5月参照基准");
      Row header = sheet.createRow(1);
      int col = 0;
      if (includeSeqHeader) {
        header.createCell(col++).setCellValue("序号");
      }
      header.createCell(col++).setCellValue("价表影响因素名称");
      header.createCell(col++).setCellValue("简称");
      header.createCell(col++).setCellValue("取价来源");
      header.createCell(col++).setCellValue("价格");
      header.createCell(col++).setCellValue("单位");
      header.createCell(col).setCellValue("价格-原价");

      Row row = sheet.createRow(dataRowIndex + 1);
      col = 0;
      if (includeSeqHeader) {
        if (!blankSeq) {
          row.createCell(col).setCellValue(64);
        }
        col++;
      }
      row.createCell(col++).setCellValue("上月宁波宝新SUS304S/Bδ0.6出厂价-900元");
      row.createCell(col++).setCellValue("SUS304/2Bδ0.6-900");
      row.createCell(col++).setCellValue("出厂价");
      row.createCell(col++).setCellValue(16.4);
      row.createCell(col++).setCellValue("公斤");
      row.createCell(col).setCellValue(16.2);
      wb.write(out);
      return out.toByteArray();
    }
  }

  private Path findSampleFactorWorkbook() {
    Path fromWorkspace = Path.of("decrypted-sheets/影响因素.xlsx");
    if (Files.exists(fromWorkspace)) {
      return fromWorkspace;
    }
    return Path.of("../../decrypted-sheets/影响因素.xlsx");
  }
}
