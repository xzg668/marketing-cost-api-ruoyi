package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedWorkbookDetectionResult;
import com.sanhua.marketingcost.enums.PriceLinkedWorkbookType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-02 联动价工作簿类型识别")
class PriceLinkedWorkbookTypeDetectorTest {

  private final PriceLinkedWorkbookTypeDetectorImpl detector =
      new PriceLinkedWorkbookTypeDetectorImpl();

  @Test
  @DisplayName("原标准模板只识别到一个标准导入 Sheet")
  void detectsStandardWorkbook() throws Exception {
    byte[] bytes = workbook(workbook -> addStandardHeader(
        workbook.createSheet("importdata1"), 0));

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "联动价.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.STANDARD);
    assertThat(result.getStandardCandidateSheets()).containsExactly("importdata1");
    assertThat(result.getType2CandidateSheets()).isEmpty();
  }

  @Test
  @DisplayName("同时存在标准 Sheet 和业务计算 Sheet 时识别为 TYPE2")
  void detectsType2WorkbookWithStandardImportSheet() throws Exception {
    byte[] bytes = workbook(workbook -> {
      addType2Header(workbook.createSheet("Sheet1"), 5);
      addStandardHeader(workbook.createSheet("importdata1"), 0);
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "任意名称.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
    assertThat(result.getType2CandidateSheets()).containsExactly("Sheet1");
    assertThat(result.getStandardCandidateSheets()).containsExactly("importdata1");
  }

  @Test
  @DisplayName("修改文件名不会改变识别结果")
  void ignoresOriginalFilename() throws Exception {
    byte[] bytes = workbook(workbook -> addStandardHeader(
        workbook.createSheet("标准数据"), 0));

    PriceLinkedWorkbookDetectionResult original = detect(bytes, "联动价.xls");
    PriceLinkedWorkbookDetectionResult renamed = detect(bytes, "完全无关的文件名-2029.xlsx");

    assertThat(renamed.getType()).isEqualTo(original.getType());
    assertThat(renamed.getStandardCandidateSheets())
        .isEqualTo(original.getStandardCandidateSheets());
  }

  @Test
  @DisplayName("修改两个 Sheet 名并调换顺序仍识别为 TYPE2")
  void ignoresSheetNamesAndOrder() throws Exception {
    byte[] bytes = workbook(workbook -> {
      addStandardHeader(workbook.createSheet("任意资料甲"), 3);
      addType2Header(workbook.createSheet("任意业务乙"), 8);
      workbook.setSheetOrder("任意业务乙", 0);
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "renamed.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
    assertThat(result.getType2CandidateSheets()).containsExactly("任意业务乙");
    assertThat(result.getStandardCandidateSheets()).containsExactly("任意资料甲");
  }

  @Test
  @DisplayName("表头前增加空行且表头含换行空格仍能识别")
  void detectsHeadersAfterBlankRowsAndWhitespace() throws Exception {
    byte[] bytes = workbook(workbook -> {
      Sheet sheet = workbook.createSheet("业务数据");
      Row header = sheet.createRow(12);
      header.createCell(0).setCellValue(" U9\n代码 ");
      header.createCell(1).setCellValue("供应商 名称");
      header.createCell(2).setCellValue("现含税价");
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "rows.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
  }

  @Test
  @DisplayName("隐藏辅助 Sheet 不参与候选")
  void ignoresHiddenAuxiliarySheet() throws Exception {
    byte[] bytes = workbook(workbook -> {
      addType2Header(workbook.createSheet("可见业务"), 0);
      addStandardHeader(workbook.createSheet("可见标准"), 0);
      addType2Header(workbook.createSheet("隐藏副本"), 0);
      workbook.setSheetHidden(workbook.getSheetIndex("隐藏副本"), true);
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "hidden.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
    assertThat(result.getType2CandidateSheets()).containsExactly("可见业务");
  }

  @Test
  @DisplayName("两个类型2业务候选明确返回 AMBIGUOUS")
  void rejectsTwoType2Candidates() throws Exception {
    byte[] bytes = workbook(workbook -> {
      addType2Header(workbook.createSheet("业务一"), 0);
      addType2Header(workbook.createSheet("业务二"), 3);
      addStandardHeader(workbook.createSheet("标准资料"), 0);
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "ambiguous.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.AMBIGUOUS);
    assertThat(result.getType2CandidateSheets()).containsExactly("业务一", "业务二");
    assertThat(result.getMessage()).contains("多个同类候选");
  }

  @Test
  @DisplayName("两个标准导入候选明确返回 AMBIGUOUS")
  void rejectsTwoStandardCandidates() throws Exception {
    byte[] bytes = workbook(workbook -> {
      addStandardHeader(workbook.createSheet("标准一"), 0);
      addStandardHeader(workbook.createSheet("标准二"), 4);
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "ambiguous-standard.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.AMBIGUOUS);
    assertThat(result.getStandardCandidateSheets()).containsExactly("标准一", "标准二");
  }

  @Test
  @DisplayName("缺少任一关键表头时不能误识别")
  void returnsUnknownWhenKeyHeaderIsMissing() throws Exception {
    byte[] bytes = workbook(workbook -> {
      Sheet sheet = workbook.createSheet("缺字段");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("U9代码");
      header.createCell(1).setCellValue("供应商名称");
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "missing.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.UNKNOWN);
  }

  @Test
  @DisplayName("完全无关 Excel 返回 UNKNOWN")
  void returnsUnknownForUnrelatedWorkbook() throws Exception {
    byte[] bytes = workbook(workbook -> {
      Sheet sheet = workbook.createSheet("统计");
      Row header = sheet.createRow(0);
      header.createCell(0).setCellValue("月份");
      header.createCell(1).setCellValue("销售额");
      header.createCell(2).setCellValue("备注");
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "unrelated.xlsx");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.UNKNOWN);
    assertThat(result.getStandardCandidateSheets()).isEmpty();
    assertThat(result.getType2CandidateSheets()).isEmpty();
  }

  @Test
  @DisplayName("只有一个业务计算候选时仍识别为 TYPE2")
  void detectsSingleType2CandidateWithoutStandardSheet() throws Exception {
    byte[] bytes = workbook(workbook -> addType2Header(
        workbook.createSheet("计算数据"), 0));

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "type2-only.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
  }

  @Test
  @DisplayName("同一 Sheet 同时具备两种核心表头时 TYPE2 优先")
  void type2WinsWhenOneSheetMatchesBothStructures() throws Exception {
    byte[] bytes = workbook(workbook -> {
      Sheet sheet = workbook.createSheet("组合数据");
      Row header = sheet.createRow(0);
      String[] headers = {
          "物料代码", "供应商名称", "供应商代码", "单价", "是否含税", "U9代码", "现含税价"
      };
      for (int column = 0; column < headers.length; column++) {
        header.createCell(column).setCellValue(headers[column]);
      }
    });

    PriceLinkedWorkbookDetectionResult result = detect(bytes, "both.xls");

    assertThat(result.getType()).isEqualTo(PriceLinkedWorkbookType.TYPE2);
    assertThat(result.getStandardCandidateSheets()).containsExactly("组合数据");
    assertThat(result.getType2CandidateSheets()).containsExactly("组合数据");
  }

  private PriceLinkedWorkbookDetectionResult detect(byte[] bytes, String filename) {
    return detector.detect(new ByteArrayInputStream(bytes), filename);
  }

  private byte[] workbook(WorkbookWriter writer) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      writer.write(workbook);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private void addStandardHeader(Sheet sheet, int rowIndex) {
    writeHeaders(
        sheet.createRow(rowIndex),
        "组织", "来源", "供应商名称", "供应商代码", "物料名称", "物料代码",
        "单位", "联动公式", "单价", "是否含税");
  }

  private void addType2Header(Sheet sheet, int rowIndex) {
    writeHeaders(
        sheet.createRow(rowIndex),
        "序号", "产品名称", "U9代码", "型号规格", "供应商名称", "现含税价", "现不含税价");
  }

  private void writeHeaders(Row row, String... headers) {
    for (int column = 0; column < headers.length; column++) {
      row.createCell(column).setCellValue(headers[column]);
    }
  }

  @FunctionalInterface
  private interface WorkbookWriter {
    void write(XSSFWorkbook workbook) throws Exception;
  }
}
