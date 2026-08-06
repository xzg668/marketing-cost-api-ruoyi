package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-03 类型2工作簿原始解析")
class PriceLinkedType2WorkbookParserTest {

  private final PriceLinkedType2WorkbookParserImpl parser =
      new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl());

  @Test
  @DisplayName("xlsx 中位置变化、合并标题、空白行和公式行均可解析")
  void parsesShiftedXlsxWorkbook() throws Exception {
    byte[] bytes = workbook(false, workbook -> addCompleteType2Workbook(workbook, true));

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "类型2.xlsx");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getBusinessSheetName()).isEqualTo("任意业务表");
    assertThat(result.getBusinessHeaderRowNumber()).isEqualTo(8);
    assertThat(result.getStandardSheetName()).isEqualTo("任意标准表");
    assertThat(result.getStandardHeaderRowNumber()).isEqualTo(4);
    assertThat(result.getFactorRows()).hasSize(2);
    assertThat(result.getFactorRows().get(0).getShortName()).isEqualTo("1#Cu");
    assertThat(result.getFactorRows().get(0).getPriceCellRef()).isEqualTo("E2");
    assertThat(result.getProductRows()).hasSize(2);
    assertThat(result.getStandardRows()).hasSize(2);
  }

  @Test
  @DisplayName("xls 与 xlsx 使用同一解析规则")
  void parsesLegacyXlsWorkbook() throws Exception {
    byte[] bytes = workbook(true, workbook -> addCompleteType2Workbook(workbook, true));

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "类型2.xls");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getFactorRows()).extracting("shortName")
        .containsExactly("1#Cu", "1#Zn");
    assertThat(result.getProductRows()).extracting("materialCode")
        .containsExactly("0012345678", "0098765432");
    assertThat(result.getStandardRows()).extracting("materialCode")
        .containsExactly("0012345678", "0098765432");
  }

  @Test
  @DisplayName("公式行保留真实公式和缓存结果，纯值行照常形成原始行")
  void preservesFormulaAndValueRows() throws Exception {
    byte[] bytes = workbook(false, workbook -> addCompleteType2Workbook(workbook, true));

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "formula.xlsx");
    PriceLinkedType2ProductRow formulaRow = result.getProductRows().get(0);
    PriceLinkedType2ProductRow valueRow = result.getProductRows().get(1);

    assertThat(formulaRow.getTaxIncludedFormula())
        .isEqualTo("G9*$E$2/1000+H9+$E$3");
    assertThat(formulaRow.getFormulaCellRef()).isEqualTo("I9");
    assertThat(formulaRow.getTaxIncludedPrice()).isNotNull();
    assertThat(formulaRow.getTaxExcludedPrice()).isNotNull();
    assertThat(valueRow.getTaxIncludedFormula()).isNull();
    assertThat(valueRow.getTaxIncludedPrice()).isEqualByComparingTo("12.34");
  }

  @Test
  @DisplayName("影响因素表头存在时非法价格不会中断解析且错误可定位")
  void reportsInvalidFactorPriceWithLocation() throws Exception {
    byte[] bytes = workbook(false, workbook -> {
      Sheet business = workbook.createSheet("业务");
      writeRow(business.createRow(1),
          "序号", "影响因素名称", "简称", "取价来源", "价格", "单位");
      writeRow(business.createRow(2),
          "1", "铜价", "1#Cu", "平均价", "不是数字", "公斤");
      addProductHeader(business, 6);
      addProductValueRow(business, 7, 12345678, "供应商甲", 10);
      addStandardSheet(workbook, 0);
    });

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "invalid.xlsx");

    assertThat(result.getFactorRows()).hasSize(1);
    assertThat(result.getFactorRows().getFirst().getPrice()).isNull();
    assertThat(result.getErrors()).singleElement()
        .satisfies(error -> {
          assertThat(error.getSheetName()).isEqualTo("业务");
          assertThat(error.getRowNumber()).isEqualTo(3);
          assertThat(error.getCellRef()).isEqualTo("E3");
          assertThat(error.getMessage()).contains("不是合法数字");
        });
  }

  @Test
  @DisplayName("公式缓存结果不可用时保留公式并返回精确错误")
  void keepsFormulaWhenCachedResultIsUnavailable() throws Exception {
    byte[] bytes = workbook(false, workbook -> {
      Sheet business = workbook.createSheet("业务");
      addProductHeader(business, 3);
      Row row = business.createRow(4);
      row.createCell(0).setCellValue(1);
      row.createCell(1).setCellValue("产品");
      row.createCell(2).setCellValue("00001234");
      row.createCell(4).setCellValue("只");
      row.createCell(5).setCellValue("供应商甲");
      row.createCell(8).setCellFormula("NA()");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      addStandardSheet(workbook, 0);
    });

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "missing-cache.xlsx");

    assertThat(result.getProductRows()).singleElement()
        .satisfies(row -> {
          assertThat(row.getTaxIncludedFormula()).isEqualTo("NA()");
          assertThat(row.getTaxIncludedPrice()).isNull();
        });
    assertThat(result.getErrors()).singleElement()
        .satisfies(error -> {
          assertThat(error.getSheetName()).isEqualTo("业务");
          assertThat(error.getRowNumber()).isEqualTo(5);
          assertThat(error.getCellRef()).isEqualTo("I5");
          assertThat(error.getMessage()).contains("公式缓存值");
        });
  }

  @Test
  @DisplayName("数字料号按普通字符串保存并保留单元格格式中的前导零")
  void preservesNumericMaterialCodesAsPlainStrings() throws Exception {
    byte[] bytes = workbook(false, workbook -> addCompleteType2Workbook(workbook, false));

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "codes.xlsx");

    assertThat(result.getProductRows().getFirst().getMaterialCode())
        .isEqualTo("0012345678")
        .doesNotContainIgnoringCase("E");
    assertThat(result.getStandardRows().getFirst().getMaterialCode())
        .isEqualTo("0012345678")
        .doesNotContainIgnoringCase("E");
  }

  @Test
  @DisplayName("只读解析结果及其列表不能被调用方修改")
  void exposesReadOnlyIntermediateLists() throws Exception {
    byte[] bytes = workbook(false, workbook -> addCompleteType2Workbook(workbook, true));

    PriceLinkedType2WorkbookParseResult result = parse(bytes, "readonly.xlsx");

    assertThatThrownBy(() -> result.getFactorRows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.getProductRows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.getStandardRows().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> result.getErrors().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("原始解析器不依赖 Mapper、Repository 或数据库写服务")
  void hasNoPersistenceDependency() {
    assertThat(Arrays.stream(PriceLinkedType2WorkbookParserImpl.class.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(field -> field.getType().getName()))
        .containsExactly(
            "com.sanhua.marketingcost.service.PriceLinkedWorkbookTypeDetector");
  }

  private PriceLinkedType2WorkbookParseResult parse(byte[] bytes, String filename) {
    return parser.parse(new ByteArrayInputStream(bytes), filename);
  }

  private byte[] workbook(boolean xls, WorkbookWriter writer) throws Exception {
    try (Workbook workbook = xls ? new HSSFWorkbook() : new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      writer.write(workbook);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private void addCompleteType2Workbook(Workbook workbook, boolean evaluate) {
    Sheet business = workbook.createSheet("任意业务表");
    writeRow(business.createRow(1),
        "1", "上月16日至本月15日长江1#电解铜含税平均价", "1#Cu", "平均价", 90, "公斤");
    writeRow(business.createRow(2),
        "2", "上月16日至本月15日长江1#电解锌含税平均价", "1#Zn", "平均价", 21.68, "公斤");
    business.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(4, 4, 0, 8));
    business.createRow(4).createCell(0).setCellValue("股份联动价格计算明细");
    addProductHeader(business, 7);
    addProductFormulaRow(business, 7, 12345678, "供应商甲");
    business.createRow(9);
    addProductValueRow(business, 10, 98765432, "供应商乙", 12.34);
    addStandardSheet(workbook, 3);
    if (evaluate) {
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
    }
  }

  private void addProductHeader(Sheet business, int rowIndex) {
    writeRow(
        business.createRow(rowIndex),
        "序号",
        "产品名称",
        "U9代码",
        "型号规格",
        "单位",
        "供应商名称",
        "黄铜毛重(g)",
        "加工费",
        "现含税价",
        "现不含税价");
  }

  private void addProductFormulaRow(
      Sheet business, int headerRowIndex, double materialCode, String supplierName) {
    int rowIndex = headerRowIndex + 1;
    Row row = business.createRow(rowIndex);
    row.createCell(0).setCellValue(1);
    row.createCell(1).setCellValue("产品一");
    numericCode(row.createCell(2), materialCode);
    row.createCell(3).setCellValue("FPQ-001");
    row.createCell(4).setCellValue("只");
    row.createCell(5).setCellValue(supplierName);
    row.createCell(6).setCellValue(10);
    row.createCell(7).setCellValue(1);
    int excelRowNumber = rowIndex + 1;
    row.createCell(8).setCellFormula(
        "G" + excelRowNumber + "*$E$2/1000+H" + excelRowNumber + "+$E$3");
    row.createCell(9).setCellFormula("I" + excelRowNumber + "/1.13");
  }

  private void addProductValueRow(
      Sheet business,
      int rowIndex,
      double materialCode,
      String supplierName,
      double includedPrice) {
    Row row = business.createRow(rowIndex);
    row.createCell(0).setCellValue(2);
    row.createCell(1).setCellValue("产品二");
    numericCode(row.createCell(2), materialCode);
    row.createCell(3).setCellValue("FPQ-002");
    row.createCell(4).setCellValue("只");
    row.createCell(5).setCellValue(supplierName);
    row.createCell(8).setCellValue(includedPrice);
    row.createCell(9).setCellValue(includedPrice / 1.13);
  }

  private void addStandardSheet(Workbook workbook, int headerRowIndex) {
    Sheet standard = workbook.createSheet("任意标准表");
    writeRow(
        standard.createRow(headerRowIndex),
        "组织",
        "来源",
        "供应商名称",
        "供应商代码",
        "采购分类",
        "物料名称",
        "物料代码",
        "规格型号",
        "单位",
        "联动公式",
        "单价",
        "是否含税",
        "生效日期",
        "失效日期",
        "订单类型");
    addStandardRow(standard, headerRowIndex + 1, 12345678, "供应商甲", 7001);
    addStandardRow(standard, headerRowIndex + 2, 98765432, "供应商乙", 7002);
  }

  private void addStandardRow(
      Sheet sheet,
      int rowIndex,
      double materialCode,
      String supplierName,
      double supplierCode) {
    Row row = sheet.createRow(rowIndex);
    row.createCell(0).setCellValue("股份");
    row.createCell(1).setCellValue("采购");
    row.createCell(2).setCellValue(supplierName);
    numericCode(row.createCell(3), supplierCode);
    row.createCell(4).setCellValue("铜件");
    row.createCell(5).setCellValue("产品");
    numericCode(row.createCell(6), materialCode);
    row.createCell(7).setCellValue("FPQ");
    row.createCell(8).setCellValue("只");
    row.createCell(9).setCellValue("[1#Cu]");
    row.createCell(10).setCellValue(10);
    row.createCell(11).setCellValue(false);
    row.createCell(14).setCellValue("联动");
  }

  private void numericCode(Cell cell, double value) {
    CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
    DataFormat dataFormat = cell.getSheet().getWorkbook().createDataFormat();
    style.setDataFormat(dataFormat.getFormat("0000000000"));
    cell.setCellStyle(style);
    cell.setCellValue(value);
  }

  private void writeRow(Row row, Object... values) {
    for (int column = 0; column < values.length; column++) {
      Object value = values[column];
      if (value instanceof Number number) {
        row.createCell(column).setCellValue(number.doubleValue());
      } else {
        row.createCell(column).setCellValue(String.valueOf(value));
      }
    }
  }

  @FunctionalInterface
  private interface WorkbookWriter {
    void write(Workbook workbook) throws Exception;
  }
}
