package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ProductPartCostRuleExcelTest {
  ProductPartCostRuleExcel excel=new ProductPartCostRuleExcel();
  XSSFWorkbook template() throws Exception { return new XSSFWorkbook(new ByteArrayInputStream(excel.template())); }
  Row row(XSSFWorkbook book,int index,String code,String model,String rate) {
    var row=book.getSheetAt(0).createRow(index);
    String[] values={"00100001","产品","规格","型号",code,"零件",model,"采购件",rate};
    for(int i=0;i<9;i++)if(values[i]!=null)row.createCell(i).setCellValue(values[i]);
    return row;
  }
  ProductPartCostRuleExcel.Parsed parse(XSSFWorkbook book)throws Exception {
    var out=new ByteArrayOutputStream();book.write(out);return excel.parse(new ByteArrayInputStream(out.toByteArray()));
  }
  @Test void templateHasNineFieldsInstructionsTextCodesPercentAndDropdownsWithoutImportableExamples()throws Exception {
    try(var book=template()) {
      assertThat(book.getNumberOfSheets()).isEqualTo(2);
      assertThat(book.getSheetAt(0).getLastRowNum()).isZero();
      assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short)9);
      assertThat(book.getSheetAt(0).getColumnStyle(0).getDataFormatString()).isEqualTo("@");
      assertThat(book.getSheetAt(0).getColumnStyle(8).getDataFormatString()).contains("%");
      assertThat(book.getSheetAt(0).getDataValidations()).hasSize(2);
      assertThat(book.getSheetAt(0).getDataValidations().getFirst().getValidationConstraint().getExplicitListValues()).containsExactly("采购件","制造件");
      assertThat(book.getSheetAt(1).getRow(11).getCell(0).getStringCellValue()).isEqualTo("00100001");
      assertThat(parse(book).errors()).extracting(e->e.message()).containsExactly("请从第2行开始填写至少一行配置");
      String capture=System.getProperty("part.cost.template.capture");
      if(capture!=null)java.nio.file.Files.write(java.nio.file.Path.of(capture),excel.template());
    }
  }
  @Test void roundTripKeepsLeadingZerosModelOnlyZeroAndNumericOrTextPercent()throws Exception {
    try(var book=template()) {
      row(book,1,"00001234","M","10%");row(book,2,null,"MODEL","0%");
      row(book,4,"00005678","M","20%").getCell(8).setCellValue(.2);
      row(book,5,"00009999","M","0.0000000001%");
      var result=parse(book);assertThat(result.errors()).isEmpty();assertThat(result.rows()).hasSize(4);
      assertThat(result.rows().getFirst().input().productCode()).isEqualTo("00100001");
      assertThat(result.rows().getFirst().input().partCode()).isEqualTo("00001234");
      assertThat(result.rows().getFirst().input().manufactureRate()).isEqualByComparingTo(".1");
      assertThat(result.rows().get(1).input().manufactureRate()).isZero();
      assertThat(result.rows().get(2).input().manufactureRate()).isEqualByComparingTo(".2");
      assertThat(result.rows().get(3).input().manufactureRate()).isEqualByComparingTo("0.000000000001");
    }
  }
  @Test void damagedHeaderAndMissingSheetAreRejected()throws Exception {
    try(var book=template()) {
      book.getSheetAt(0).getRow(0).getCell(8).setCellValue("费率");assertThat(parse(book).errors().getFirst().message()).contains("表头");
      book.setSheetName(0,"wrong");assertThat(parse(book).errors().getFirst().message()).contains("配置填写");
    }
  }
  @Test void numericIdentifiersFormulaAndBadRatesReportExactRows()throws Exception {
    try(var book=template()) {
      row(book,1,"0001",null,"10%").getCell(4).setCellValue(1);
      row(book,2,"0002",null,"10%").getCell(8).setCellFormula("1/10");
      row(book,3,"0003",null,"oops");
      row(book,4,"0004",null,"");
      row(book,5,"0005","MODEL","10%").getCell(6).setCellValue(1);
      var result=parse(book);assertThat(result.errors()).hasSize(5);
      assertThat(result.errors()).extracting(e->e.row()).containsExactly(2,3,4,5,6);
      assertThat(result.errors().getFirst().message()).contains("文本");
      assertThat(result.errors().get(1).message()).contains("公式");
    }
  }
  @Test void extraContentMergedCellsAndOversizedSheetsAreRejected()throws Exception {
    try(var book=template()) {
      row(book,1,"0001",null,"10%").createCell(9).setCellValue("extra");
      assertThat(parse(book).errors().getFirst().message()).contains("多余");
      book.getSheetAt(0).createRow(5001);assertThat(parse(book).errors().getFirst().message()).contains("5000");
      book.getSheetAt(0).removeRow(book.getSheetAt(0).getRow(5001));
      book.getSheetAt(0).addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1,1,0,1));
      assertThat(parse(book).errors().getFirst().message()).contains("合并");
    }
  }
  @Test void corruptFileGivesActionableTemplateError() {
    assertThatThrownBy(()->excel.parse(new ByteArrayInputStream(new byte[]{1,2,3})))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("模板");
  }
}
