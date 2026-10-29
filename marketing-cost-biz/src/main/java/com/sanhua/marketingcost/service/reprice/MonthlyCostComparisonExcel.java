package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonService.Page;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.ObjectIdentity;
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
      {"spec","父件规格"},{"model","父件型号"},{"oa","首次报价OA单号"},{"change","变化"}};
  private static final String[][] MONTH={{"unit","计量单位"},{"completedAt","生效时间"},{"financeCuPrice","核算基价（元/公斤）"},
      {"MATERIAL","材料费（1）"},{"DIRECT_LABOR","直接人工工资（2）"},{"INDIRECT_LABOR","辅助人工工资（3）"},
      {"MANUFACTURE","制造费用（4）"},{"LOSS","净损失成本（5）"},{"MANUFACTURE_COST","制造成本（6）"},
      {"productProperty","产品属性"},{"lossRate","净损失率"},{"wageSource","直接人工工资来源"},{"unapproved","未审批项"}};
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
    var columns=columns(first.comparison());
    try(var wb=new SXSSFWorkbook(100)) {
      wb.setCompressTempFiles(true);
      try {
        var sheet=wb.createSheet("制造成本明细对比");var styles=new Styles(wb);int rowNumber=4;long products=0;
        org.apache.poi.ss.usermodel.Row title=sheet.createRow(0);title.setHeightInPoints(28);text(title,0,"制造成本明细对比表",styles.title);
        sheet.addMergedRegion(new CellRangeAddress(0,0,0,3));
        org.apache.poi.ss.usermodel.Row note=sheet.createRow(1);note.setHeightInPoints(32);text(note,0,
            "材料费按部品；人工等费用按父件。\n新增/减少差异为－；空白不代表0。",styles.note);
        sheet.addMergedRegion(new CellRangeAddress(1,1,0,3));
        org.apache.poi.ss.usermodel.Row group=sheet.createRow(2),header=sheet.createRow(3);group.setHeightInPoints(24);header.setHeightInPoints(42);
        for(int i=0;i<columns.size();i++){
          var column=columns.get(i);text(group,i,i<4?"产品与部品":column.group(),styles.header);text(header,i,column.label(),styles.header);
          sheet.setColumnWidth(i,(column.key().equals("completedAt")?21:column.key().equals("oa")?22:
              column.key().equals("parentName")||column.key().equals("name")?18:column.key().equals("wageSource")?22:column.key().equals("unit")?8:column.key().contains(":")||column.key().equals("financeCuPrice")||Arrays.stream(Metric.values()).anyMatch(m->m.name().equals(column.key()))?16:13)*256);
        }
        for(int start=0;start<columns.size();){int end=start;while(end+1<columns.size()&&end!=3&&columns.get(start).group().equals(columns.get(end+1).group()))end++;
          if(end>start)sheet.addMergedRegion(new CellRangeAddress(2,2,start,end));start=end+1;}
        Page current=first;
        while(true) {
          if(!current.comparison().baselineMonth().equals(first.comparison().baselineMonth())
              || !current.comparison().comparisonMonth().equals(first.comparison().comparisonMonth())
              || !current.comparison().metrics().equals(first.comparison().metrics()) || current.totalProducts()!=first.totalProducts())
            throw new IllegalStateException("导出期间比较范围发生变化");
          var children=new HashMap<ObjectIdentity,List<MonthlyCostComparison.Row>>();
          for(var line:current.comparison().rows())children.computeIfAbsent(line.key().object(),unused->new ArrayList<>()).add(line);
          for(var product:current.comparison().products()) {
            int start=rowNumber;
            var lines=children.getOrDefault(product.object(),List.of());
            if(rowNumber+1L+lines.size()>1_048_576)throw new IllegalArgumentException("导出超过工作表行数上限，请按OA或产品筛选");
            writeRow(sheet.createRow(rowNumber++),columns,product,null,styles);
            for(var line:lines)writeRow(sheet.createRow(rowNumber++),columns,product,line,styles);
            if(rowNumber-start>1)for(int col=0;col<2;col++)sheet.addMergedRegion(new CellRangeAddress(start,rowNumber-1,col,col));
            products++;
          }
          if(products>=first.totalProducts())break;
          current=nextPage.apply(current.page()+1);
          if(current.comparison().products().isEmpty())throw new IllegalStateException("导出产品分页不完整");
        }
        if(products!=first.totalProducts())throw new IllegalStateException("导出产品数量不一致");
        sheet.createFreezePane(4,4);sheet.setRepeatingRows(new CellRangeAddress(2,3,-1,-1));
        sheet.setRepeatingColumns(new CellRangeAddress(-1,-1,0,3));
        sheet.setAutobreaks(true);sheet.setFitToPage(false);
        var print=sheet.getPrintSetup();print.setLandscape(true);print.setPaperSize(PrintSetup.A3_PAPERSIZE);print.setScale((short)50);
        // Keep each complete month together across horizontal print pages; parent and component identities repeat.
        sheet.setColumnBreak(FIXED.length+MONTH.length-1);
        if(columns.size()>FIXED.length+MONTH.length*2)sheet.setColumnBreak(FIXED.length+MONTH.length*2-1);
        sheet.setMargin(Sheet.LeftMargin,.25);sheet.setMargin(Sheet.RightMargin,.25);sheet.setMargin(Sheet.TopMargin,.4);sheet.setMargin(Sheet.BottomMargin,.4);
        sheet.getFooter().setCenter("第 &P 页 / 共 &N 页");wb.setPrintArea(0,0,columns.size()-1,0,rowNumber-1);
        wb.write(output);return rowNumber-4;
      } finally {wb.dispose();}
    }
  }
  private void writeRow(org.apache.poi.ss.usermodel.Row row,List<Column> columns,Product product,MonthlyCostComparison.Row part,Styles styles) {
    row.setHeightInPoints(34);boolean total=part==null;
    for(int i=0;i<columns.size();i++) {
      var col=columns.get(i);Object value;String explanation=null;
      if(col.group().equals("固定信息"))value=switch(col.key()){
        case "parentCode"->product.parent().code();case "parentName"->product.parent().name();
        case "code"->total?null:part.key().materialCode();case "name"->total?"父件汇总":part.comparison()!=null?part.comparison().name():part.baseline().name();
        case "spec"->product.parent().specification();case "model"->product.parent().model();case "oa"->product.object().oaNo();
        case "change"->(total?product.change():part.change()).label;default->null;};
      else if(col.key().contains(":")) {
        var key=col.key().split(":");var differences=total?product.differences():part.differences();var d=differences.get(Metric.valueOf(key[0]));
        value=d==null?null:key[1].equals("amount")?d.amount():d.rate();
        if(d!=null)explanation=d.explanation();
        if(value instanceof String s && !s.equals("-")) value=key[1].equals("rate")?new BigDecimal(s.replace("%","")).movePointLeft(2):new BigDecimal(s);
      } else {
        boolean baseline=col.group().equals(columns.get(FIXED.length).group());
        Object side=total?(baseline?product.baseline():product.comparison()):(baseline?part.baseline():part.comparison());
        value=monthValue(side,col.key());
      }
      var cell=row.createCell(i);
      boolean rate=col.key().endsWith(":rate");
      cell.setCellStyle(styles.style(total,value,rate));
      if(value instanceof BigDecimal number) {
        if(number.precision()>15){cell.setCellValue(rate?number.movePointRight(2).toPlainString()+"%":number.toPlainString());cell.setCellStyle(total?styles.totalText:styles.text);}
        else cell.setCellValue(number.doubleValue());
      } else if(value instanceof LocalDateTime date)cell.setCellValue(date);
      else if(value!=null)cell.setCellValue(value.toString());
      if(value==null && explanation!=null && (explanation.contains("分母") || explanation.contains("未记录"))) {
        var helper=row.getSheet().getWorkbook().getCreationHelper();var anchor=helper.createClientAnchor();
        anchor.setCol1(i);anchor.setCol2(i+3);anchor.setRow1(row.getRowNum());anchor.setRow2(row.getRowNum()+3);
        var comment=row.getSheet().createDrawingPatriarch().createCellComment(anchor);comment.setAuthor("产品成本核算");
        comment.setString(helper.createRichTextString(explanation));cell.setCellComment(comment);
      }
    }
  }
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
