package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-03 类型2公式单元格快照")
class PriceLinkedType2CellSnapshotTest {

  private final PriceLinkedType2WorkbookParserImpl parser =
      new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl());

  @Test
  @DisplayName("公式引用的多个本行字段和因素价格均保留来源、数值与单位")
  void snapshotsEveryReferencedInputCell() throws Exception {
    byte[] bytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet business = workbook.createSheet("计算");
      writeRow(business.createRow(1),
          "1", "电解铜含税平均价", "1#Cu", "平均价", 90, "公斤");
      writeRow(business.createRow(2),
          "2", "电解锌含税平均价", "1#Zn", "平均价", 21.68, "公斤");
      writeRow(
          business.createRow(6),
          "序号",
          "产品名称",
          "U9代码",
          "型号规格",
          "单位",
          "供应商名称",
          "黄铜毛重(g)",
          "含税加工费(元/只)",
          "现含税价",
          "现不含税价");
      Row product = business.createRow(7);
      product.createCell(0).setCellValue(1);
      product.createCell(1).setCellValue("分配器");
      product.createCell(2).setCellValue("109910977");
      product.createCell(4).setCellValue("只");
      product.createCell(5).setCellValue("浙江华亿");
      product.createCell(6).setCellValue(10);
      product.createCell(7).setCellValue(1);
      product.createCell(8).setCellFormula("G8*$E$2/1000+H8+$E$3");
      product.createCell(9).setCellFormula("I8/1.13");

      Sheet standard = workbook.createSheet("标准");
      writeRow(
          standard.createRow(0),
          "供应商名称", "供应商代码", "物料代码", "单价", "是否含税");
      writeRow(standard.createRow(1),
          "浙江华亿", "20001", "109910977", 4.16, "FALSE");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.write(output);
      bytes = output.toByteArray();
    }

    PriceLinkedType2WorkbookParseResult result = parser.parse(
        new ByteArrayInputStream(bytes), "snapshot.xlsx");

    PriceLinkedType2ProductRow row = result.getProductRows().getFirst();
    Map<String, PriceLinkedType2CellSnapshot> snapshots = row.getReferencedCells().stream()
        .collect(Collectors.toMap(
            PriceLinkedType2CellSnapshot::getCellRef,
            Function.identity()));
    assertThat(snapshots).containsOnlyKeys("G8", "E2", "H8", "E3");
    assertThat(snapshots.get("G8").getHeader()).isEqualTo("黄铜毛重(g)");
    assertThat(snapshots.get("G8").getUnit()).isEqualTo("g");
    assertThat(snapshots.get("G8").getNumericValue()).isEqualByComparingTo("10");
    assertThat(snapshots.get("H8").getHeader()).isEqualTo("含税加工费(元/只)");
    assertThat(snapshots.get("H8").getUnit()).isEqualTo("元/只");
    assertThat(snapshots.get("E2").getHeader()).isEqualTo("1#Cu");
    assertThat(snapshots.get("E2").getUnit()).isEqualTo("公斤");
    assertThat(snapshots.get("E2").getNumericValue()).isEqualByComparingTo("90");
    assertThat(snapshots.get("E3").getHeader()).isEqualTo("1#Zn");
    assertThat(snapshots.get("E3").getNumericValue()).isEqualByComparingTo("21.68");
  }

  @Test
  @DisplayName("标准 Sheet 保留全部原始字段及其单元格位置")
  void snapshotsAllStandardFieldsWithoutMergingRows() throws Exception {
    byte[] bytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet business = workbook.createSheet("计算");
      writeRow(
          business.createRow(2),
          "U9代码", "产品名称", "供应商名称", "现含税价");
      writeRow(business.createRow(3), "00001234", "产品", "供应商", 10);
      Sheet standard = workbook.createSheet("来源");
      writeRow(
          standard.createRow(3),
          "组织", "供应商名称", "供应商代码", "物料代码", "单价", "是否含税", "自定义备注");
      writeRow(
          standard.createRow(4),
          "股份", "供应商", "0007", "00001234", 8.84955752, "FALSE", "原始备注");
      workbook.write(output);
      bytes = output.toByteArray();
    }

    PriceLinkedType2WorkbookParseResult result = parser.parse(
        new ByteArrayInputStream(bytes), "raw-standard.xlsx");

    assertThat(result.getProductRows()).hasSize(1);
    assertThat(result.getStandardRows()).singleElement()
        .satisfies(row -> {
          assertThat(row.getMaterialCode()).isEqualTo("00001234");
          assertThat(row.getSupplierName()).isEqualTo("供应商");
          assertThat(row.getSupplierCode()).isEqualTo("0007");
          assertThat(row.getCells()).extracting("header")
              .contains("组织", "单价", "是否含税", "自定义备注");
          assertThat(row.getCells()).extracting("cellRef")
              .contains("A5", "E5", "F5", "G5");
        });
  }

  @Test
  @DisplayName("物理空白在原始快照中明确标记")
  void distinguishesPhysicalBlankFromText() throws Exception {
    byte[] bytes;
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet business = workbook.createSheet("计算");
      writeRow(business.createRow(1),
          "1", "电解铜含税平均价", "1#Cu", "平均价", 90, "公斤");
      writeRow(
          business.createRow(3),
          "序号", "产品名称", "U9代码", "型号规格", "单位", "供应商名称",
          "空白输入", "文本输入", "现含税价", "现不含税价");
      Row product = business.createRow(4);
      product.createCell(0).setCellValue(1);
      product.createCell(1).setCellValue("产品");
      product.createCell(2).setCellValue("10001");
      product.createCell(5).setCellValue("供应商");
      product.createCell(6, org.apache.poi.ss.usermodel.CellType.BLANK);
      product.createCell(7).setCellValue("未维护");
      product.createCell(8).setCellFormula("$E$2+G5");

      Sheet standard = workbook.createSheet("标准");
      writeRow(
          standard.createRow(0),
          "供应商名称", "供应商代码", "物料代码", "单价", "是否含税");
      writeRow(standard.createRow(1), "供应商", "S01", "10001", 90, "TRUE");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.write(output);
      bytes = output.toByteArray();
    }

    PriceLinkedType2WorkbookParseResult result = parser.parse(
        new ByteArrayInputStream(bytes), "blank-snapshot.xlsx");
    PriceLinkedType2CellSnapshot blank = result.getProductRows().getFirst()
        .getReferencedCells().stream()
        .filter(cell -> "G5".equals(cell.getCellRef()))
        .findFirst()
        .orElseThrow();

    assertThat(blank.isBlankCell()).isTrue();
    assertThat(blank.getSourceCellType()).isEqualTo("BLANK");
    assertThat(blank.getNumericValue()).isNull();
    assertThat(blank.getDisplayValue()).isEmpty();
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
}
