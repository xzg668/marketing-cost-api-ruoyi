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
    var result=MonthlyCostComparison.compare(A,B,metrics,List.of(MonthlyCostComparisonTest.selected(new Panel(id,A,1,List.of(old,part(id,"000C","20")),pm))),List.of(MonthlyCostComparisonTest.selected(new Panel(id,B,2,List.of(newer,part(id,"000C","20"),part(id,"000D","2")),pn))));
    return new Page(result,page,1,total,List.of());
  }
  byte[] render(Page first,java.util.function.IntFunction<Page> next)throws Exception{try(var output=new ByteArrayOutputStream()){new MonthlyCostComparisonExcel().write(first,next,output);return output.toByteArray();}}
  @Test void mainSheetMatchesTheSuppliedPrototypeColumnOrder() throws Exception {
    var page=sample("OA",1,1,List.of(Metric.MATERIAL,Metric.MANUFACTURE_COST));
    var columns=MonthlyCostComparisonExcel.columns(page.comparison());
    assertThat(columns).hasSize(36);
    assertThat(columns.subList(0,10).stream().map(MonthlyCostComparisonExcel.Column::label)).containsExactly(
        "一级编码","一级名称","父件编码","父件名称","父件规格","父件型号","计量单位","生效时间","首次报价OA单号","变化");
    assertThat(columns.subList(10,21).stream().map(MonthlyCostComparisonExcel.Column::label)).containsExactly(
        "核算基价","材料费","直接人工工资","辅助人工工资","制造费用","净损失成本","制造成本","产品属性","未审批项","净损失率","直接人工工资来源");
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(page,n->null)))) {
      var sheet=wb.getSheetAt(0);
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("一级编码");
      assertThat(sheet.getRow(1).getCell(10).getStringCellValue()).isEqualTo("核算基价");
      assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)2);
      assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("父件汇总");
    }
  }
  @Test void realWorkbookRetainsComponentMaterialAndSingleProductFeesWithDynamicMonthGroups() throws Exception {
    var first=sample("000OA",1,1,List.of(Metric.MATERIAL,Metric.DIRECT_LABOR,Metric.MANUFACTURE_COST));
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(first,n->{throw new AssertionError();})))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);var s=wb.getSheetAt(0);
      assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("一级编码");assertThat(s.getRow(0).getCell(2).getStringCellValue()).isEqualTo("父件编码");
      assertThat(s.getRow(0).getCell(10).getStringCellValue()).isEqualTo("2025年12月成本明细");assertThat(s.getRow(0).getCell(21).getStringCellValue()).isEqualTo("2026年3月成本明细");
      assertThat(s.getRow(1).getLastCellNum()).isEqualTo((short)38);
      assertThat(s.getMergedRegions()).allMatch(r->r.getLastRow()<2);
      assertThat(s.getRow(2).getCell(2).getStringCellValue()).isEqualTo("000A");assertThat(s.getRow(3).getCell(0).getStringCellValue()).isEqualTo("000B");
      assertThat(s.getRow(3).getCell(1).getCellType()).isEqualTo(CellType.STRING);assertThat(s.getRow(3).getCell(1).getStringCellValue()).isEqualTo("=SUM(1,2)");
      assertThat(s.getRow(2).getCell(12).getNumericCellValue()).isZero();assertThat(s.getRow(3).getCell(12).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(s.getRow(3).getCell(32).getNumericCellValue()).isEqualTo(-2);assertThat(s.getRow(3).getCell(33).getNumericCellValue()).isEqualTo(-.2);
      assertThat(s.getRow(5).getCell(11).getCellType()).isEqualTo(CellType.BLANK);assertThat(s.getRow(5).getCell(32).getStringCellValue()).isEqualTo("-");
      assertThat(s.getRow(2).getCell(35).getCellType()).isEqualTo(CellType.BLANK);assertThat(s.getRow(2).getCell(35).getCellComment().getString().getString()).contains("分母");
      assertThat(s.getRow(3).getCell(7).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026,3,25,12,30));
      assertThat(s.getPrintSetup().getLandscape()).isTrue();assertThat(s.getPaneInformation().isFreezePane()).isTrue();assertThat(s.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short)2);
    }
  }
  @Test void noSelectedDifferencesProducesOnlyBaseAndTwoMonthColumns()throws Exception {
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(sample("OA",1,1,List.of()),n->null)))){assertThat(wb.getSheetAt(0).getRow(1).getLastCellNum()).isEqualTo((short)32);}
  }
  @Test void allProductPagesAreRenderedInsteadOfOnlyTheVisiblePage()throws Exception {
    var pages=new ArrayList<Integer>();
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(sample("OA1",1,2,List.of(Metric.MATERIAL)),n->{pages.add(n);return sample("OA2",n,2,List.of(Metric.MATERIAL));})))){
      assertThat(pages).containsExactly(2);assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(9);
      assertThat(wb.getSheetAt(0).getRow(6).getCell(8).getStringCellValue()).isEqualTo("OA2");
    }
  }
  @Test void sheetRolloverKeepsEveryProductAndItsLinesAcrossInputPages() throws Exception {
    try(var output=new ByteArrayOutputStream()) {
      int rows=new MonthlyCostComparisonExcel().write(sample("OA1",1,2,List.of(Metric.MATERIAL)),
          n->sample("OA2",n,2,List.of(Metric.MATERIAL)),output,6);
      assertThat(rows).isEqualTo(8);
      try(var wb=new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
        for(int i=0;i<2;i++) {
          var sheet=wb.getSheetAt(i);sheet.validateMergedRegions();
          assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("一级编码");
          assertThat(sheet.getLastRowNum()).isEqualTo(5);
          assertThat(sheet.getRow(2).getCell(8).getStringCellValue()).isEqualTo("OA"+(i+1));
          assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("000B");
          assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("000D");
          assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
          assertThat(wb.getPrintArea(i)).contains("$A$1:");
        }
      }
    }
  }
  @Test void oneProductLargerThanASheetKeepsOneSummaryAndAllContinuationLines() throws Exception {
    try(var output=new ByteArrayOutputStream()) {
      int rows=new MonthlyCostComparisonExcel().write(sample("OA",1,1,List.of(Metric.MATERIAL)),
          n->{throw new AssertionError("one product only");},output,4);
      assertThat(rows).isEqualTo(4);
      try(var wb=new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
        assertThat(wb.getSheetAt(0).getRow(2).getCell(1).getStringCellValue()).isEqualTo("父件汇总");
        assertThat(wb.getSheetAt(1).getRow(2).getCell(0).getStringCellValue()).isEqualTo("000C");
        assertThat(wb.getSheetAt(1).getRow(3).getCell(0).getStringCellValue()).isEqualTo("000D");
        for(var sheet:wb) {
          ((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).validateMergedRegions();
          assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("000A");
        }
      }
    }
  }
  @Test void repeatedMissingDifferencesUseOneExplanationPerColumnOnEachSheet() throws Exception {
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(sample("OA1",1,3,List.of(Metric.DIRECT_LABOR)),
        n->sample("OA"+n,n,3,List.of(Metric.DIRECT_LABOR)))))) {
      long notes=0;
      for(var row:wb.getSheetAt(0))for(var cell:row)if(cell.getCellComment()!=null)notes++;
      assertThat(notes).isEqualTo(1);
      assertThat(wb.getSheetAt(0).getRow(2).getCell(33).getCellComment().getString().getString()).contains("分母");
    }
  }
  @Test void historicalPartsWithoutPathsExportTheirAmountsWithoutFalseAdditions() throws Exception {
    var before=MonthlyCostComparisonTest.unmatched("LEGACY:1:2","X","10");
    var after=MonthlyCostComparisonTest.line(MonthlyCostComparisonTest.OA,"root/X","X","12");
    var oldPanel=MonthlyCostComparisonTest.panel(MonthlyCostComparisonTest.OA,A,before);
    var newPanel=MonthlyCostComparisonTest.panel(MonthlyCostComparisonTest.OA,B,after);
    var result=MonthlyCostComparison.compare(A,B,List.of(Metric.MATERIAL),List.of(oldPanel),List.of(newPanel));
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(new Page(result,1,20,1,List.of()),n->null)))) {
      var sheet=wb.getSheetAt(0);
      assertThat(sheet.getRow(3).getCell(9).getStringCellValue()).isEqualTo("历史部品待匹配");
      assertThat(sheet.getRow(4).getCell(9).getStringCellValue()).isEqualTo("历史部品待匹配");
      assertThat(sheet.getRow(3).getCell(11).getNumericCellValue()).isEqualTo(10);
      assertThat(sheet.getRow(3).getCell(22).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(sheet.getRow(4).getCell(11).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(sheet.getRow(4).getCell(22).getNumericCellValue()).isEqualTo(12);
      assertThat(sheet.getRow(3).getCell(32).getStringCellValue()).isEqualTo("-");
    }
  }
  @Test void decimalBeyondExcelPrecisionIsPreservedAsTextRatherThanRounded()throws Exception {
    var page=sample("OA",1,1,List.of(Metric.MATERIAL));var row=page.comparison().rows().getFirst();var old=row.baseline();
    var huge=new BigDecimal("123456789012345.123456");
    var replacement=new Line(old.key(),old.parent(),old.name(),old.unit(),old.completedAt(),old.financeCuPrice(),Map.of(Metric.MATERIAL,huge),old.productProperty(),old.lossRate(),old.wageSource(),old.unapproved());
    var rows=new ArrayList<>(page.comparison().rows());rows.set(0,new MonthlyCostComparison.Row(row.objectKey(),row.key(),row.parent(),replacement,row.comparison(),row.change(),row.differences()));
    var result=new Result(A,B,page.comparison().metrics(),rows,page.comparison().products(),List.of());
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(new Page(result,1,1,1,List.of()),n->null)))){
      assertThat(wb.getSheetAt(0).getRow(3).getCell(11).getCellType()).isEqualTo(CellType.STRING);
      assertThat(wb.getSheetAt(0).getRow(3).getCell(11).getStringCellValue()).isEqualTo(huge.toPlainString());
    }
  }
  @Test void latePageFailureDoesNotLeaveOrReturnAPartialDownload()throws Exception {
    var comparison=mock(MonthlyCostComparisonService.class);
    var scope=new ExportScope("COMMERCIAL",A,B,List.of(Metric.MATERIAL),Set.of(A,B),2);
    when(comparison.selectForExport(any(),any())).thenReturn(scope);
    when(comparison.exportPage(eq(scope),anyInt(),anyInt(),anyList()))
        .thenReturn(sample("OA1",1,2,List.of(Metric.MATERIAL))).thenThrow(new IllegalStateException("saved source damaged"));
    var session=mock(org.mybatis.spring.SqlSessionTemplate.class);
    var service=new MonthlyCostComparisonExportService(comparison,new MonthlyCostComparisonExcel(),session,new com.fasterxml.jackson.databind.ObjectMapper());
    Set<Path> before;try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){before=files.filter(p->p.getFileName().toString().startsWith("monthly-cost-comparison-")).collect(java.util.stream.Collectors.toSet());}
    assertThatThrownBy(()->service.export(new Request("2025-12","2026-03",List.of(Metric.MATERIAL),null,null,null,1,20))).hasMessage("saved source damaged");
    org.mockito.Mockito.verify(session,org.mockito.Mockito.times(3)).clearCache();
    org.mockito.Mockito.verify(comparison,never()).compare(any());
    try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))){assertThat(files.filter(p->p.getFileName().toString().startsWith("monthly-cost-comparison-")).toList()).containsExactlyInAnyOrderElementsOf(before);}
  }
  @Test void uncalculatedCustomerStaysInActualWorkbookWithBlankCostsAndBothReasons()throws Exception {
    var a=new MonthlyCostComparisonScope.Selected("COMMERCIAL","000A","OA_HEADER_CUSTOMER","Customer-A",null,A.toString(),"OA-OLD",1,null,"MR-A",1L,null,10,2,"FAILED","missing material X");
    var b=new MonthlyCostComparisonScope.Selected("COMMERCIAL","000A","OA_HEADER_CUSTOMER","Customer-A",null,B.toString(),"OA-NEW",2,null,"MR-B",2L,null,11,3,"PENDING","waiting");
    var comparison=MonthlyCostComparison.compare(A,B,List.of(Metric.MATERIAL),List.of(new SelectedPanel(a,null)),List.of(new SelectedPanel(b,null)));
    try(var wb=new XSSFWorkbook(new ByteArrayInputStream(render(new Page(comparison,1,20,1,List.of()),n->null)))) {
      var s=wb.getSheetAt(0);assertThat(s.getLastRowNum()).isEqualTo(2);
      assertThat(s.getRow(2).getCell(8).getCellComment().getString().getString()).contains("Customer-A");
      assertThat(s.getRow(2).getCell(11).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(s.getRow(2).getCell(22).getCellType()).isEqualTo(CellType.BLANK);
      assertThat(s.getRow(2).getCell(8).getCellComment().getString().getString()).contains("核算失败");
      assertThat(s.getRow(2).getCell(8).getCellComment().getString().getString()).contains("missing material X");
      assertThat(s.getRow(2).getCell(8).getCellComment().getString().getString()).contains("OA-NEW");
      assertThat(s.getRow(2).getCell(32).getStringCellValue()).isEqualTo("-");
    }
  }
}
