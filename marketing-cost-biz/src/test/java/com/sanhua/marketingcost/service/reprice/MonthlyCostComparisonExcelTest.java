package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonService.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class MonthlyCostComparisonExcelTest {
  static final YearMonth A=YearMonth.of(2025,12),B=YearMonth.of(2026,3);
  static final Parent P=new Parent("000A","中文父件","DN25","型号一");
  static Line part(ObjectIdentity id,String code,String amount){return new Line(new Key(id,List.of("A/"+code),"A",code),P,code.equals("000B")?"=SUM(1,2)":"中文部品","件",LocalDateTime.of(2026,3,25,12,30),new BigDecimal("75.6"),Map.of(Metric.MATERIAL,new BigDecimal(amount)),"标准品",new BigDecimal("0.01"),"CMS",null);}
  static Page sample(String oa,int page,long total,List<Metric> metrics){
    var id=new ObjectIdentity("COMMERCIAL",oa,page);var old=part(id,"000B","10");var newer=part(id,"000B","8");
    var pm=new ProductMonth(1,P,old.completedAt(),old.financeCuPrice(),Map.of(Metric.MATERIAL,new BigDecimal("30"),Metric.DIRECT_LABOR,BigDecimal.ZERO,Metric.MANUFACTURE_COST,new BigDecimal("35.5")),"标准品",old.lossRate(),"CMS",null);
    var pn=new ProductMonth(2,P,old.completedAt(),old.financeCuPrice(),Map.of(Metric.MATERIAL,new BigDecimal("30"),Metric.DIRECT_LABOR,BigDecimal.ONE,Metric.MANUFACTURE_COST,new BigDecimal("36.5")),"标准品",old.lossRate(),"CMS参考料号 000Z","直接人工工资");
    var result=MonthlyCostComparison.compare(A,B,metrics,List.of(new Panel(id,A,1,List.of(old,part(id,"000C","20")),pm)),List.of(new Panel(id,B,2,List.of(newer,part(id,"000C","20"),part(id,"000D","2")),pn)));
    return new Page(result,page,1,total,List.of());
  }
  byte[] render(Page first,java.util.function.IntFunction<Page> next)throws Exception{try(var output=new ByteArrayOutputStream()){new MonthlyCostComparisonExcel().write(first,next,output);return output.toByteArray();}}
  @Test void realWorkbookRetainsComponentMaterialAndSingleProductFeesWithDynamicMonthGroups() throws Exception {
    var first=sample("000OA",1,1,List.of(Metric.MATERIAL,Metric.DIRECT_LABOR,Metric.MANUFACTURE_COST));
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(first,n->{throw new AssertionError();})))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);var s=wb.getSheetAt(0);
      assertThat(s.getRow(3).getCell(0).getStringCellValue()).isEqualTo("父件编码");assertThat(s.getRow(3).getCell(1).getStringCellValue()).isEqualTo("父件名称");
      assertThat(s.getRow(2).getCell(8).getStringCellValue()).isEqualTo("2025-12");assertThat(s.getRow(2).getCell(21).getStringCellValue()).isEqualTo("2026-03");
      assertThat(s.getRow(3).getLastCellNum()).isEqualTo((short)40);
      assertThat(s.getMergedRegions()).anyMatch(r->r.getFirstRow()==4 && r.getLastRow()==7 && r.getFirstColumn()==0);
      assertThat(s.getRow(4).getCell(0).getStringCellValue()).isEqualTo("000A");assertThat(s.getRow(5).getCell(2).getStringCellValue()).isEqualTo("000B");
      assertThat(s.getRow(5).getCell(3).getCellType()).isEqualTo(CellType.STRING);assertThat(s.getRow(5).getCell(3).getStringCellValue()).isEqualTo("=SUM(1,2)");
      assertThat(s.getRow(4).getCell(12).getNumericCellValue()).isZero();assertThat(s.getRow(5).getCell(12).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(s.getRow(5).getCell(34).getNumericCellValue()).isEqualTo(-2);assertThat(s.getRow(5).getCell(35).getNumericCellValue()).isEqualTo(-.2);
      assertThat(s.getRow(7).getCell(11).getCellType()).isEqualTo(CellType.BLANK);assertThat(s.getRow(7).getCell(34).getStringCellValue()).isEqualTo("-");
      assertThat(s.getRow(4).getCell(37).getCellType()).isEqualTo(CellType.BLANK);assertThat(s.getRow(4).getCell(37).getCellComment().getString().getString()).contains("分母");
      assertThat(s.getRow(5).getCell(9).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026,3,25,12,30));
      assertThat(s.getPrintSetup().getLandscape()).isTrue();assertThat(s.getPaneInformation().isFreezePane()).isTrue();assertThat(s.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short)4);
    }
  }
  @Test void noSelectedDifferencesProducesOnlyBaseAndTwoMonthColumns()throws Exception {
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(sample("OA",1,1,List.of()),n->null)))){assertThat(wb.getSheetAt(0).getRow(3).getLastCellNum()).isEqualTo((short)34);}
  }
  @Test void allProductPagesAreRenderedInsteadOfOnlyTheVisiblePage()throws Exception {
    var pages=new ArrayList<Integer>();
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(sample("OA1",1,2,List.of(Metric.MATERIAL)),n->{pages.add(n);return sample("OA2",n,2,List.of(Metric.MATERIAL));})))){
      assertThat(pages).containsExactly(2);assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(11);
      assertThat(wb.getSheetAt(0).getRow(8).getCell(6).getStringCellValue()).isEqualTo("OA2");
    }
  }
  @Test void decimalBeyondExcelPrecisionIsPreservedAsTextRatherThanRounded()throws Exception {
    var page=sample("OA",1,1,List.of(Metric.MATERIAL));var row=page.comparison().rows().getFirst();var old=row.baseline();
    var huge=new BigDecimal("123456789012345.123456");
    var replacement=new Line(old.key(),old.parent(),old.name(),old.unit(),old.completedAt(),old.financeCuPrice(),Map.of(Metric.MATERIAL,huge),old.productProperty(),old.lossRate(),old.wageSource(),old.unapproved());
    var rows=new ArrayList<>(page.comparison().rows());rows.set(0,new MonthlyCostComparison.Row(row.key(),row.parent(),replacement,row.comparison(),row.change(),row.differences()));
    var result=new Result(A,B,page.comparison().metrics(),rows,page.comparison().products(),List.of());
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(new Page(result,1,1,1,List.of()),n->null)))){
      assertThat(wb.getSheetAt(0).getRow(5).getCell(11).getCellType()).isEqualTo(CellType.STRING);
      assertThat(wb.getSheetAt(0).getRow(5).getCell(11).getStringCellValue()).isEqualTo(huge.toPlainString());
    }
  }
  @Test void latePageFailureDoesNotLeaveOrReturnAPartialDownload()throws Exception {
    var comparison=mock(MonthlyCostComparisonService.class);when(comparison.compare(any())).thenReturn(sample("OA1",1,2,List.of(Metric.MATERIAL))).thenThrow(new IllegalStateException("saved source damaged"));
    var session=mock(org.mybatis.spring.SqlSessionTemplate.class);
    var service=new MonthlyCostComparisonExportService(comparison,new MonthlyCostComparisonExcel(),session);
    Set<Path> before;try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){before=files.filter(p->p.getFileName().toString().startsWith("monthly-cost-comparison-")).collect(java.util.stream.Collectors.toSet());}
    assertThatThrownBy(()->service.export(new Request("2025-12","2026-03",List.of(Metric.MATERIAL),null,null,null,1,20))).hasMessage("saved source damaged");
    org.mockito.Mockito.verify(session,org.mockito.Mockito.times(2)).clearCache();
    try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){assertThat(files.filter(p->p.getFileName().toString().startsWith("monthly-cost-comparison-")).toList()).containsExactlyInAnyOrderElementsOf(before);}
  }
}
