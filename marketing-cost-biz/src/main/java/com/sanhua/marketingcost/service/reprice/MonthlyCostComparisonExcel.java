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
  private static final String[][] FIXED={{"parentCode","父件编码"},{"parentName","父件名称"},{"code","一级编码"},{"name","一级名称"},
      {"spec","父件规格"},{"model","父件型号"},{"customer","客户"},{"change","变化"}};
  private static final String[][] MONTH={{"unit","计量单位"},{"completedAt","生效时间"},{"financeCuPrice","核算基价（元/公斤）"},
      {"MATERIAL","材料费（1）"},{"DIRECT_LABOR","直接人工工资（2）"},{"INDIRECT_LABOR","辅助人工工资（3）"},
      {"MANUFACTURE","制造费用（4）"},{"LOSS","净损失成本（5）"},{"MANUFACTURE_COST","制造成本（6）"},
      {"productProperty","产品属性"},{"lossRate","净损失率"},{"wageSource","直接人工工资来源"},{"unapproved","未审批项"},
      {"status","核算状态"},{"oa","采用的OA / 原OA"},{"sources","来源数"},{"reason","未核算 / 匹配说明"}};
  public static List<Column> columns(Result result) {
    var columns=new ArrayList<Column>();for(var f:FIXED)columns.add(new Column("固定信息",f[0],f[1]));
    for(String month:List.of(result.baselineMonth().toString(),result.comparisonMonth().toString()))
      for(var field:MONTH)columns.add(new Column(month,field[0],field[1]));
    for(var metric:Metric.values()) if(result.metrics().contains(metric)) {
      columns.add(new Column(metric.label+"差异",metric.name()+":amount","差异金额"));
      columns.add(new Column(metric.label+"差异",metric.name()+":rate","差异率"));
    }
    return List.copyOf(columns);
  }
  public int write(Page first,IntFunction<Page> nextPage,OutputStream output) throws IOException {
    return write(first,nextPage,output,org.apache.poi.ss.SpreadsheetVersion.EXCEL2007.getMaxRows());
  }
  int write(Page first,IntFunction<Page> nextPage,OutputStream output,int maximumSheetRows) throws IOException {
    if(maximumSheetRows<5 || maximumSheetRows>org.apache.poi.ss.SpreadsheetVersion.EXCEL2007.getMaxRows())
      throw new IllegalArgumentException("工作表必须保留表头和至少一行明细");
    var columns=columns(first.comparison());
    try(var wb=new SXSSFWorkbook(100)) {
      wb.setCompressTempFiles(true);
      try {
        var writer=new Sheets(wb,columns,new Styles(wb),maximumSheetRows);
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
            int start=writer.rowNumber;
            writer.write(product,null);
            for(var line:lines) {
              if(writer.full()) {
                writer.mergeIdentity(start);writer.nextSheet();start=writer.rowNumber;
              }
              writer.write(product,line);
            }
            writer.mergeIdentity(start);
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
    private int rowNumber,dataRows,lastMergedEnd;

    Sheets(SXSSFWorkbook workbook,List<Column> columns,Styles styles,int maximumRows) {
      this.workbook=workbook;this.columns=columns;this.styles=styles;this.maximumRows=maximumRows;
      createSheet();
    }
    void beginProduct(long rows) {
      if(maximumRows-rowNumber<Math.min(rows,maximumRows-4L))nextSheet();
    }
    boolean full(){return rowNumber==maximumRows;}
    void write(Product product,MonthlyCostComparison.Row part) {
      if(full())throw new IllegalStateException("工作表未切换到下一页");
      writeRow(sheet.createRow(rowNumber++),columns,product,part,styles,explained);
      dataRows=Math.addExact(dataRows,1);
    }
    void mergeIdentity(int start) {
      if(rowNumber-start<=1)return;
      if(start<=lastMergedEnd)throw new IllegalStateException("产品合并区域重叠");
      // Row ranges are consecutive, disjoint product segments below the four header rows.
      // Validating against every earlier merge would make large exports quadratic.
      for(int column=0;column<2;column++)
        sheet.addMergedRegionUnsafe(new CellRangeAddress(start,rowNumber-1,column,column));
      lastMergedEnd=rowNumber-1;
    }
    void nextSheet(){finishSheet();createSheet();}
    void finishSheet(){workbook.setPrintArea(workbook.getNumberOfSheets()-1,0,columns.size()-1,0,rowNumber-1);}
    private void createSheet() {
      int number=workbook.getNumberOfSheets()+1;
      sheet=workbook.createSheet(number==1?"制造成本明细对比":"制造成本明细对比_"+number);
      rowNumber=4;lastMergedEnd=3;explained.clear();
      var title=sheet.createRow(0);title.setHeightInPoints(28);
      text(title,0,number==1?"制造成本明细对比表":"制造成本明细对比表（续 "+number+"）",styles.title);
      sheet.addMergedRegion(new CellRangeAddress(0,0,0,3));
      var note=sheet.createRow(1);note.setHeightInPoints(48);
      text(note,0,"按料号＋客户比较，材料费按部品、人工等按父件。\n未核算留空、不补零；客户身份缺失不猜测匹配。\n差异率无分母或分项未记录时留空，说明见各列首次批注。",styles.note);
      sheet.addMergedRegion(new CellRangeAddress(1,1,0,3));
      var group=sheet.createRow(2);var header=sheet.createRow(3);
      group.setHeightInPoints(24);header.setHeightInPoints(42);
      for(int i=0;i<columns.size();i++) {
        var column=columns.get(i);
        text(group,i,i<4?"产品与部品":column.group(),styles.header);text(header,i,column.label(),styles.header);
        sheet.setColumnWidth(i,(column.key().equals("completedAt")?21:column.key().equals("oa")?22:
            column.key().equals("parentName")||column.key().equals("name")?18:column.key().equals("wageSource")?22:
            column.key().equals("unit")?8:column.key().contains(":")||column.key().equals("financeCuPrice")
            ||Arrays.stream(Metric.values()).anyMatch(m->m.name().equals(column.key()))?16:13)*256);
      }
      for(int start=0;start<columns.size();) {
        int end=start;
        while(end+1<columns.size()&&end!=3&&columns.get(start).group().equals(columns.get(end+1).group()))end++;
        if(end>start)sheet.addMergedRegion(new CellRangeAddress(2,2,start,end));
        start=end+1;
      }
      sheet.createFreezePane(4,4);sheet.setRepeatingRows(new CellRangeAddress(2,3,-1,-1));
      sheet.setRepeatingColumns(new CellRangeAddress(-1,-1,0,3));sheet.setAutobreaks(true);sheet.setFitToPage(false);
      var print=sheet.getPrintSetup();print.setLandscape(true);print.setPaperSize(PrintSetup.A3_PAPERSIZE);print.setScale((short)50);
      sheet.setColumnBreak(FIXED.length+MONTH.length-1);
      if(columns.size()>FIXED.length+MONTH.length*2)sheet.setColumnBreak(FIXED.length+MONTH.length*2-1);
      sheet.setMargin(Sheet.LeftMargin,.25);sheet.setMargin(Sheet.RightMargin,.25);sheet.setMargin(Sheet.TopMargin,.4);sheet.setMargin(Sheet.BottomMargin,.4);
      sheet.getFooter().setCenter("第 &P 页 / 共 &N 页");
    }
  }
  private void writeRow(org.apache.poi.ss.usermodel.Row row,List<Column> columns,Product product,MonthlyCostComparison.Row part,Styles styles,Set<String> explained) {
    row.setHeightInPoints(34);boolean total=part==null;
    for(int i=0;i<columns.size();i++) {
      var col=columns.get(i);Object value;String explanation=null;
      if(col.group().equals("固定信息"))value=switch(col.key()){
        case "parentCode"->product.parent().code();case "parentName"->product.parent().name();
        case "code"->total?null:part.key().materialCode();case "name"->total?"父件汇总":part.comparison()!=null?part.comparison().name():part.baseline().name();
        case "spec"->product.parent().specification();case "model"->product.parent().model();
        case "customer"->product.identity().matched()?product.identity().customerValue():"无法按客户匹配";
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
      boolean rate=col.key().endsWith(":rate");
      cell.setCellStyle(styles.style(total,value,rate));
      if(value instanceof BigDecimal number) {
        if(number.precision()>15){cell.setCellValue(rate?number.movePointRight(2).toPlainString()+"%":number.toPlainString());cell.setCellStyle(total?styles.totalText:styles.text);}
        else cell.setCellValue(number.doubleValue());
      } else if(value instanceof LocalDateTime date)cell.setCellValue(date);
      else if(value!=null)cell.setCellValue(value.toString());
      // POI keeps comments in memory even for SXSSF. Explain each reason once per column/sheet.
      if(value==null && explanation!=null && (explanation.contains("分母") || explanation.contains("未记录"))
          && explained.add(i+":"+explanation)) {
        var helper=row.getSheet().getWorkbook().getCreationHelper();var anchor=helper.createClientAnchor();
        anchor.setCol1(i);anchor.setCol2(i+3);anchor.setRow1(row.getRowNum());anchor.setRow2(row.getRowNum()+3);
        var comment=row.getSheet().createDrawingPatriarch().createCellComment(anchor);comment.setAuthor("产品成本核算");
        comment.setString(helper.createRichTextString(explanation));cell.setCellComment(comment);
      }
    }
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
    final CellStyle text,money,date,rate,totalText,totalMoney,totalDate,totalRate,header,title,note;
    Styles(Workbook wb){
      var normal=wb.createFont();normal.setFontName("宋体");normal.setFontHeightInPoints((short)9);
      var bold=wb.createFont();bold.setFontName("宋体");bold.setFontHeightInPoints((short)9);bold.setBold(true);
      text=base(wb,normal);text.setDataFormat(wb.createDataFormat().getFormat("@"));money=copy(wb,text,"0.000000");date=copy(wb,text,"yyyy-mm-dd hh:mm:ss");rate=copy(wb,text,"0.00%");
      totalText=base(wb,bold);totalText.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());totalText.setFillPattern(FillPatternType.SOLID_FOREGROUND);totalText.setDataFormat(text.getDataFormat());
      totalMoney=copy(wb,totalText,"0.000000");totalDate=copy(wb,totalText,"yyyy-mm-dd hh:mm:ss");totalRate=copy(wb,totalText,"0.00%");
      header=base(wb,bold);header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);header.setAlignment(HorizontalAlignment.CENTER);
      var titleFont=wb.createFont();titleFont.setFontName("宋体");titleFont.setFontHeightInPoints((short)14);titleFont.setBold(true);
      title=base(wb,titleFont);title.setAlignment(HorizontalAlignment.LEFT);note=base(wb,normal);
    }
    CellStyle style(boolean total,Object value,boolean percent){return value instanceof LocalDateTime?(total?totalDate:date):value instanceof BigDecimal?(percent?(total?totalRate:rate):(total?totalMoney:money)):(total?totalText:text);}
    static CellStyle base(Workbook wb,Font font){var s=wb.createCellStyle();s.setFont(font);s.setWrapText(true);s.setVerticalAlignment(VerticalAlignment.CENTER);
      s.setBorderBottom(BorderStyle.THIN);s.setBorderTop(BorderStyle.THIN);s.setBorderLeft(BorderStyle.THIN);s.setBorderRight(BorderStyle.THIN);return s;}
    static CellStyle copy(Workbook wb,CellStyle source,String format){var s=wb.createCellStyle();s.cloneStyleFrom(source);s.setDataFormat(wb.createDataFormat().getFormat(format));return s;}
  }
}
