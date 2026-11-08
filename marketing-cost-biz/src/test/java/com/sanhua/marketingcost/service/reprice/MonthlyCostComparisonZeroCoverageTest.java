package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.io.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;

class MonthlyCostComparisonZeroCoverageTest {
  @Test void threeUnchangedProductsRemainPairedAndTheirActualExcelDifferencesAreNumericZero() throws Exception {
    var november=YearMonth.of(2026,11);var december=YearMonth.of(2026,12);
    var before=new ArrayList<SelectedPanel>();var after=new ArrayList<SelectedPanel>();
    for(int i=1;i<=3;i++){before.add(panel(november,i,100+i));after.add(panel(december,i,200+i));}
    var result=MonthlyCostComparison.compare(november,december,List.of(Metric.MATERIAL,Metric.MANUFACTURE_COST),before,after);
    assertThat(result.products()).hasSize(3);assertThat(result.rows()).hasSize(3);
    for(var product:result.products()) {
      assertThat(product.baseline()).isNotNull();assertThat(product.comparison()).isNotNull();
      assertThat(product.differences().values()).allSatisfy(d->{assertThat(d.amount()).isEqualTo("0.000000");assertThat(d.rate()).isEqualTo("0.00%");});
    }
    var bytes=new ByteArrayOutputStream();new MonthlyCostComparisonExcel().write(new MonthlyCostComparisonService.Page(result,1,20,3,List.of()),n->{throw new AssertionError();},bytes);
    try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes.toByteArray()))) {
      var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(7);
      for(int row=2;row<=6;row+=2) {
        assertThat(sheet.getRow(row).getCell(32).getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(sheet.getRow(row).getCell(32).getNumericCellValue()).isZero();
        assertThat(sheet.getRow(row).getCell(33).getNumericCellValue()).isZero();
        assertThat(sheet.getRow(row).getCell(34).getNumericCellValue()).isZero();
        assertThat(sheet.getRow(row).getCell(35).getNumericCellValue()).isZero();
        assertThat(sheet.getRow(row+1).getCell(34).getCellType()).isEqualTo(CellType.BLANK);
      }
    }
  }
  static SelectedPanel panel(YearMonth month,int index,long version) {
    String product="PRODUCT-"+index,oa="OA-"+month+"-"+index;
    var owner=new ObjectIdentity("COMMERCIAL",oa,index);var parent=new Parent(product,"产品"+index,"规格","型号");
    var time=month.atDay(1).atTime(9,0);var material=new BigDecimal("10.00");
    var line=new Line(new Key(owner,List.of(product+"/RAW"),product,"RAW-"+index),parent,"原料","件",time,new BigDecimal("90"),Map.of(Metric.MATERIAL,material),"标准品",new BigDecimal("0.01"),"CMS",null);
    var productMonth=new ProductMonth(version,parent,time,new BigDecimal("90"),Map.of(Metric.MATERIAL,material,Metric.DIRECT_LABOR,new BigDecimal("2"),Metric.INDIRECT_LABOR,BigDecimal.ONE,Metric.MANUFACTURE,BigDecimal.ONE,Metric.LOSS,BigDecimal.ONE,Metric.MANUFACTURE_COST,new BigDecimal("15")),"标准品",new BigDecimal("0.01"),"CMS",null);
    var selected=new MonthlyCostComparisonScope.Selected("COMMERCIAL",product,"OA_HEADER_CUSTOMER","Customer-"+index,null,month.toString(),oa,index,version,null,null,null,version,1,"SUCCESS",null);
    return new SelectedPanel(selected,new Panel(owner,month,version,List.of(line),productMonth));
  }
}
