package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonService.Page;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.IntFunction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/** Streaming presentation of the same saved comparison model used by the page. No Excel formulas. */
@Component
public class MonthlyCostComparisonExcel {
  public record Column(String group,String key,String label) {}
  private static final int HEADER_ROWS=2;
  private static final String[][] FIXED={{"code","一级编码"},{"name","一级名称"},{"parentCode","父件编码"},{"parentName","父件名称"},
      {"spec","父件规格"},{"model","父件型号"},{"unit","计量单位"},{"completedAt","生效时间"},{"oa","首次报价OA单号"},{"change","变化"}};
  private static final String[][] MONTH={{"financeCuPrice","核算基价"},{"MATERIAL","材料费"},
      {"DIRECT_LABOR","直接人工工资"},{"INDIRECT_LABOR","辅助人工工资"},{"MANUFACTURE","制造费用"},
      {"LOSS","净损失成本"},{"MANUFACTURE_COST","制造成本"},{"productProperty","产品属性"},
      {"unapproved","未审批项"},{"lossRate","净损失率"},{"wageSource","直接人工工资来源"}};
  public static List<Column> columns(Result result) {
    var columns=new ArrayList<Column>();for(var f:FIXED)columns.add(new Column("固定信息",f[0],f[1]));
    for(String month:List.of(result.baselineMonth().toString(),result.comparisonMonth().toString()))
      for(var field:MONTH)columns.add(new Column(month,field[0],field[1]));
    for(var metric:Metric.values()) if(result.metrics().contains(metric)) {
      columns.add(new Column("差异",metric.name()+":amount",metric.label+"差异金额"));
      columns.add(new Column("差异",metric.name()+":rate",metric.label+"差异率"));
    }
    return List.copyOf(columns);
  }
  public int write(Page first,IntFunction<Page> nextPage,OutputStream output) throws IOException {
    return write(first,nextPage,output,org.apache.poi.ss.SpreadsheetVersion.EXCEL2007.getMaxRows());
  }
  int write(Page first,IntFunction<Page> nextPage,OutputStream output,int maximumSheetRows) throws IOException {
    if(maximumSheetRows<HEADER_ROWS+1 || maximumSheetRows>org.apache.poi.ss.SpreadsheetVersion.EXCEL2007.getMaxRows())
      throw new IllegalArgumentException("工作表必须保留表头和至少一行明细");
    var columns=columns(first.comparison());
    try(var wb=new SXSSFWorkbook(100)) {
      wb.setCompressTempFiles(true);
      try {
        var writer=new Sheets(wb,columns,new Styles(wb),maximumSheetRows,first.comparison());
        long products=0;
        Page current=first;
        while(true) {
          if(!current.comparison().baselineMonth().equals(first.comparison().baselineMonth())
              || !current.comparison().comparisonMonth().equals(first.comparison().comparisonMonth())
              || !current.comparison().metrics().equals(first.comparison().metrics()) || current.totalProducts()!=first.totalProducts())
            throw new IllegalStateException("导出期间比较范围发生变化");
          var children=new HashMap<String,List<MonthlyCostComparison.Row>>();
          for(var line:current.comparison().rows())children.computeIfAbsent(line.objectKey(),unused->new ArrayList<>()).add(line);
          for(var product:current.comparison().products()) {
            var lines=children.getOrDefault(product.objectKey(),List.of());
            writer.beginProduct(1L+lines.size());
            writer.write(product,null);
            for(var line:lines) {
              if(writer.full()) {
                writer.nextSheet();
              }
              writer.write(product,line);
            }
            products++;
          }
          if(products>=first.totalProducts())break;
          current=nextPage.apply(current.page()+1);
          if(current.comparison().products().isEmpty())throw new IllegalStateException("导出产品分页不完整");
        }
        if(products!=first.totalProducts())throw new IllegalStateException("导出产品数量不一致");
        writer.finishSheet();
        wb.write(output);return writer.dataRows;
      } finally {wb.dispose();}
    }
  }
  /** Whole products stay together when possible; an oversized product continues without repeating its amounts. */
  private final class Sheets {
    private final SXSSFWorkbook workbook;
    private final List<Column> columns;
    private final Styles styles;
    private final int maximumRows;
    private final Set<String> explained=new HashSet<>();
    private Sheet sheet;
    private int rowNumber,dataRows;
    private final Result comparison;

    Sheets(SXSSFWorkbook workbook,List<Column> columns,Styles styles,int maximumRows,Result comparison) {
      this.workbook=workbook;this.columns=columns;this.styles=styles;this.maximumRows=maximumRows;this.comparison=comparison;
      createSheet();
    }
    void beginProduct(long rows) {
      if(maximumRows-rowNumber<Math.min(rows,maximumRows-HEADER_ROWS))nextSheet();
    }
    boolean full(){return rowNumber==maximumRows;}
    void write(Product product,MonthlyCostComparison.Row part) {
      if(full())throw new IllegalStateException("工作表未切换到下一页");
      writeRow(sheet.createRow(rowNumber++),columns,product,part,styles,explained);
      dataRows=Math.addExact(dataRows,1);
    }
    void nextSheet(){finishSheet();createSheet();}
    void finishSheet(){workbook.setPrintArea(workbook.getNumberOfSheets()-1,0,columns.size()-1,0,rowNumber-1);}
    private void createSheet() {
      int number=workbook.getNumberOfSheets()+1;
      sheet=workbook.createSheet(number==1?"制造成本明细对比":"制造成本明细对比_"+number);
      rowNumber=HEADER_ROWS;explained.clear();
      var group=sheet.createRow(0);var header=sheet.createRow(1);
      group.setHeightInPoints(32);header.setHeightInPoints(32);
      for(int i=0;i<columns.size();i++) {
        var column=columns.get(i);
        sheet.setColumnWidth(i,18*256);
        if(i<FIXED.length) {
          text(group,i,column.label(),styles.header);
          text(header,i,"",styles.header);
          sheet.addMergedRegion(new CellRangeAddress(0,1,i,i));
        } else {
          String groupLabel=column.group().equals("差异")
              ? "差异 · "+comparison.comparisonMonth()+"减"+comparison.baselineMonth()
              : java.time.YearMonth.parse(column.group()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月"))+"成本明细";
          text(group,i,groupLabel,styles.header);text(header,i,column.label(),styles.header);
        }
      }
      for(int start=FIXED.length;start<columns.size();) {
        int end=start;
        while(end+1<columns.size()&&columns.get(start).group().equals(columns.get(end+1).group()))end++;
        if(end>start)sheet.addMergedRegion(new CellRangeAddress(0,0,start,end));
        start=end+1;
      }
      sheet.createFreezePane(0,HEADER_ROWS);sheet.setRepeatingRows(new CellRangeAddress(0,1,-1,-1));
      sheet.setRepeatingColumns(new CellRangeAddress(-1,-1,0,3));sheet.setAutobreaks(true);sheet.setFitToPage(false);
      var print=sheet.getPrintSetup();print.setLandscape(true);print.setPaperSize(PrintSetup.A3_PAPERSIZE);print.setScale((short)50);
      sheet.setColumnBreak(FIXED.length+MONTH.length-1);
      if(columns.size()>FIXED.length+MONTH.length*2)sheet.setColumnBreak(FIXED.length+MONTH.length*2-1);
      sheet.setMargin(Sheet.LeftMargin,.25);sheet.setMargin(Sheet.RightMargin,.25);sheet.setMargin(Sheet.TopMargin,.4);sheet.setMargin(Sheet.BottomMargin,.4);
      sheet.getFooter().setCenter("第 &P 页 / 共 &N 页");
    }
  }
  private void writeRow(org.apache.poi.ss.usermodel.Row row,List<Column> columns,Product product,MonthlyCostComparison.Row part,Styles styles,Set<String> explained) {
    row.setHeightInPoints(32);boolean total=part==null;
    for(int i=0;i<columns.size();i++) {
      var col=columns.get(i);Object value;String explanation=null;
      if(col.group().equals("固定信息"))value=switch(col.key()){
        case "parentCode"->product.parent().code();case "parentName"->product.parent().name();
        case "code"->total?null:part.key().materialCode();case "name"->total?"父件汇总":part.comparison()!=null?part.comparison().name():part.baseline().name();
        case "spec"->product.parent().specification();case "model"->product.parent().model();
        case "unit"->monthValue(preferredSide(product,part),"unit");
        case "completedAt"->monthValue(preferredSide(product,part),"completedAt");
        case "oa"->originalOa(product);
        case "change"->(total?product.change():part.change()).label;default->null;};
      else if(col.key().contains(":")) {
        var key=col.key().split(":");var differences=total?product.differences():part.differences();var d=differences.get(Metric.valueOf(key[0]));
        value=d==null?null:key[1].equals("amount")?d.amount():d.rate();
        if(d!=null)explanation=d.explanation();
        if(value instanceof String s && !s.equals("-")) value=key[1].equals("rate")?new BigDecimal(s.replace("%","")).movePointLeft(2):new BigDecimal(s);
      } else {
        boolean baseline=col.group().equals(columns.get(FIXED.length).group());
        Object side=total?(baseline?product.baseline():product.comparison()):(baseline?part.baseline():part.comparison());
        var state=baseline?product.baselineState():product.comparisonState();
        value=switch(col.key()) {
          case "status"->state==null?"该月无核算记录":status(state.status());
          case "oa"->state==null?null:state.oaNo();case "sources"->state==null?null:state.sourceCount();
          case "reason"->state==null?null:state.reason();default->monthValue(side,col.key());
        };
      }
      var cell=row.createCell(i);
      boolean rate=col.key().endsWith(":rate") || col.key().equals("lossRate");
      cell.setCellStyle(styles.style(total,value,rate));
      if(value instanceof BigDecimal number) {
        if(number.precision()>15){cell.setCellValue(rate?number.movePointRight(2).toPlainString()+"%":number.toPlainString());cell.setCellStyle(total?styles.totalText:styles.text);}
        else cell.setCellValue(number.doubleValue());
      } else if(value instanceof LocalDateTime date)cell.setCellValue(date);
      else if(value!=null)cell.setCellValue(value.toString());
      if(total && col.key().equals("oa") && (!product.identity().matched()
          || product.baseline()==null || product.comparison()==null)) {
        var details=new StringBuilder();
        if(!product.identity().matched())details.append("客户身份缺失，无法匹配\n");
        else details.append("客户：").append(product.identity().customerValue()).append('\n');
        for(var state:new MonthlyCostComparisonScope.Selected[]{product.baselineState(),product.comparisonState()}) {
          if(state!=null)details.append(state.pricingMonth()).append("：").append(status(state.status()))
              .append("；采用OA：").append(state.oaNo()).append("；来源数：").append(state.sourceCount())
              .append(state.reason()==null?"":"；"+state.reason()).append('\n');
        }
        comment(cell,details.toString());
      }
      // POI keeps comments in memory even for SXSSF. Explain each reason once per column/sheet.
      if(value==null && explanation!=null && (explanation.contains("分母") || explanation.contains("未记录"))
          && explained.add(i+":"+explanation)) {
        comment(cell,explanation);
      }
    }
  }
  private static Object preferredSide(Product product,MonthlyCostComparison.Row part) {
    return part==null?(product.comparison()!=null?product.comparison():product.baseline())
        :(part.comparison()!=null?part.comparison():part.baseline());
  }
  private static String originalOa(Product product) {
    var a=product.baselineState();var b=product.comparisonState();
    return a!=null && (b==null || !a.month().isAfter(b.month()))?a.oaNo():b!=null?b.oaNo():product.object().oaNo();
  }
  private static void comment(Cell cell,String value) {
    var sheet=cell.getSheet();var helper=sheet.getWorkbook().getCreationHelper();var anchor=helper.createClientAnchor();
    anchor.setCol1(cell.getColumnIndex());anchor.setCol2(cell.getColumnIndex()+4);anchor.setRow1(cell.getRowIndex());anchor.setRow2(cell.getRowIndex()+5);
    var comment=sheet.createDrawingPatriarch().createCellComment(anchor);comment.setAuthor("产品成本核算");
    comment.setString(helper.createRichTextString(value));cell.setCellComment(comment);
  }
  private static String status(String value){return switch(value){
    case "SUCCESS"->"已核算";case "FAILED"->"核算失败";case "PENDING"->"等待核算";case "RUNNING"->"正在核算";
    case "WAIT_CUSTOMER"->"客户待完善";case "DATA_ISSUE"->"资料异常";default->value;};}
  private Object monthValue(Object source,String key) {
    if(source==null)return null;
    Map<Metric,BigDecimal> amounts;String unit,property,wage,unapproved;LocalDateTime completed;BigDecimal cu,loss;
    if(source instanceof ProductMonth p){amounts=p.amounts();unit=null;property=p.productProperty();wage=p.wageSource();unapproved=p.unapproved();completed=p.completedAt();cu=p.financeCuPrice();loss=p.lossRate();}
    else {var p=(Line)source;amounts=p.amounts();unit=p.unit();property=p.productProperty();wage=p.wageSource();unapproved=p.unapproved();completed=p.completedAt();cu=p.financeCuPrice();loss=p.lossRate();}
    return switch(key){case "unit"->unit;case "completedAt"->completed;case "financeCuPrice"->cu;case "productProperty"->property;
      case "lossRate"->loss;case "wageSource"->wage;case "unapproved"->unapproved;default->amounts.get(Metric.valueOf(key));};
  }
  private static void text(org.apache.poi.ss.usermodel.Row row,int col,String text,CellStyle style){var cell=row.createCell(col);cell.setCellStyle(style);cell.setCellValue(text);}
  private static class Styles {
    final CellStyle text,money,date,rate,totalText,totalMoney,totalDate,totalRate,header;
    Styles(Workbook wb){
      var normal=wb.createFont();normal.setFontName("Microsoft YaHei");normal.setFontHeightInPoints((short)11);
      var bold=wb.createFont();bold.setFontName("Microsoft YaHei");bold.setFontHeightInPoints((short)11);bold.setBold(true);
      text=base(wb,normal);text.setDataFormat(wb.createDataFormat().getFormat("@"));money=copy(wb,text,"0.00");date=copy(wb,text,"yyyy-mm-dd hh:mm");rate=copy(wb,text,"0.00%");
      totalText=base(wb,bold);totalText.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());totalText.setFillPattern(FillPatternType.SOLID_FOREGROUND);totalText.setDataFormat(text.getDataFormat());
      totalMoney=copy(wb,totalText,"0.00");totalDate=copy(wb,totalText,"yyyy-mm-dd hh:mm");totalRate=copy(wb,totalText,"0.00%");
      header=base(wb,bold);((org.apache.poi.xssf.usermodel.XSSFCellStyle)header).setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(new java.awt.Color(234,243,255)));header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setAlignment(HorizontalAlignment.CENTER);
    }
    CellStyle style(boolean total,Object value,boolean percent){return value instanceof LocalDateTime?(total?totalDate:date):value instanceof BigDecimal?(percent?(total?totalRate:rate):(total?totalMoney:money)):(total?totalText:text);}
    static CellStyle base(Workbook wb,Font font){var s=wb.createCellStyle();s.setFont(font);s.setWrapText(true);s.setVerticalAlignment(VerticalAlignment.CENTER);
      s.setBorderBottom(BorderStyle.THIN);s.setBorderTop(BorderStyle.THIN);s.setBorderLeft(BorderStyle.THIN);s.setBorderRight(BorderStyle.THIN);return s;}
    static CellStyle copy(Workbook wb,CellStyle source,String format){var s=wb.createCellStyle();s.cloneStyleFrom(source);s.setDataFormat(wb.createDataFormat().getFormat(format));return s;}
  }
}
