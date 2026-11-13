package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.dto.jjb.JjbCostExplanation.Price;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** A compact finance worksheet: saved inputs, substituted formulas and a reconciled sum. */
final class JjbCalculationExcel {
  private final Workbook workbook;
  private final Sheet sheet;
  private final CellStyle text, money, heading, title;
  private int next;

  JjbCalculationExcel(Workbook workbook) {
    this.workbook = workbook;
    sheet = workbook.createSheet("成本计算明细");
    text = style(false, false); money = style(false, false);
    money.setDataFormat(workbook.createDataFormat().getFormat("0.000000########"));
    money.setAlignment(HorizontalAlignment.RIGHT);
    heading = style(true, true); title = style(true, false);
    var font = workbook.createFont(); font.setFontName("微软雅黑"); font.setFontHeightInPoints((short)15); font.setBold(true);title.setFont(font);
    int[] widths = {26,30,25,25,12,55,18,18,18,48};
    for (int i=0;i<widths.length;i++) sheet.setColumnWidth(i,widths[i]*256);
    sheet.setDisplayGridlines(false);
  }

  void write(JjbReadModel model) {
    merged("成本计算明细",title);
    merged("产品："+model.identity().productCode()+"　报价单："+model.identity().oaNo()+"　核算月份："+model.identity().pricingMonth()+"　版本："+Objects.toString(model.identity().versionNo(),"#"+model.identity().versionId()),text);
    var explanation = model.explanation();
    if (explanation == null) {
      merged("此历史版本未保存逐项计算依据，以下为原保存总额。", text);
      total("不含税总成本1",model.totals().totalCost(), "");
      total("材料成本差异",model.totals().cuMaterialAdjustment(), "");
      total("不含税总成本2",model.totals().finalQuoteAmount(), "");
      configure(); return;
    }
    merged("一、不含税总成本1：以下项目相加",heading);
    var addition = new ArrayList<String>();
    for (var item:explanation.totalItems()) {
      Row row=row(); merge(row,0,2,item.name(),text);merge(row,3,7,item.formula(),text);amount(row,8,item.amount());merge(row,8,9,null,money);
      row.setHeightInPoints(Math.max(24, item.formula().split("\n", -1).length * 16 + 8));
      addition.add(item.amount());
    }
    total("不含税总成本1",model.totals().totalCost(),sum(addition, model.totals().totalCost()));
    merged("二、逐项材料计算：基准金额、客户金额、差异",heading);
    Row columns=row();
    String[] labels={"料号／名称","采用的价格来源","公式使用的基价","代入的客户价","实际用量","单价公式与结果","基准金额（元）","客户金额（元）","差异（元）","金额计算"};
    for(int i=0;i<labels.length;i++){columns.getCell(i).setCellValue(labels[i]);columns.getCell(i).setCellStyle(heading);}columns.setHeightInPoints(32);
    int headerRow=columns.getRowNum();
    var differences=new ArrayList<String>();
    for (var item:explanation.materials()) {
      Row row=row();
      value(row,0,Objects.toString(item.code(),"无料号")+"\n"+Objects.toString(item.name(),""));
      value(row,1,prices(item.prices(),Price::source,item.code()));
      value(row,2,prices(item.prices(),Price::baseFactors,item.code()));
      value(row,3,prices(item.prices(),Price::customerFactors,item.code()));
      value(row,4,item.quantity()+" "+Objects.toString(item.unit(),"（原版本未记录单位）"));
      value(row,5,prices(item.prices(),Price::formula,item.code()));
      amount(row,6,item.financialAmount());amount(row,7,item.customerAmount());amount(row,8,item.difference());
      value(row,9,item.formula());
      int lines=3;
      for(int c:new int[]{0,1,2,3,4,5,9}) {
        String value=row.getCell(c).getStringCellValue();
        int width=Math.max(8,sheet.getColumnWidth(c)/256-2);
        int wrapped=Arrays.stream(value.split("\n",-1)).mapToInt(s->Math.max(1,(s.codePoints().map(ch->ch>127?2:1).sum()+width-1)/width)).sum();
        lines=Math.max(lines,wrapped);
      }
      row.setHeightInPoints(Math.min(409,lines*14+10));
      differences.add(item.difference());
    }
    total("材料成本差异",model.totals().cuMaterialAdjustment(),
        sum(differences, model.totals().cuMaterialAdjustment()));
    total("不含税总成本2",model.totals().finalQuoteAmount(),model.totals().totalCost()+" + ("+model.totals().cuMaterialAdjustment()+") = "+model.totals().finalQuoteAmount());
    merged(explanation.note(),text);
    sheet.createFreezePane(1,headerRow+1);
    configure();
  }

  private static String prices(List<Price> values, Function<Price,String> field, String owner) {
    if(values.isEmpty())return "历史未记录";
    boolean multiple=values.size()>1;
    return String.join("\n",values.stream().map(p->(multiple || !Objects.equals(owner,p.materialCode()) ? p.materialCode()+"：" : "")+field.apply(p)).toList());
  }
  private static String sum(List<String> values, String saved) {
    BigDecimal actual = values.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(6, java.math.RoundingMode.HALF_UP);
    String result = (values.isEmpty() ? "0" : String.join(" + ", values.stream().map(v -> "("+v+")").toList()))
        + " = " + actual.toPlainString();
    return saved != null && actual.compareTo(new BigDecimal(saved)) == 0 ? result
        : result + "；原保存总额 " + Objects.toString(saved, "未记录") + "（历史口径不同）";
  }
  private void total(String label,String amount,String formula) {
    Row row=row();merge(row,0,2,label,heading);merge(row,3,7,formula,text);amount(row,8,amount);merge(row,8,9,null,money);
    int lines=Math.max(2,(formula.length()+90)/90);row.setHeightInPoints(Math.min(409,lines*14+6));
  }
  private Row row(){Row row=sheet.createRow(next++);row.setHeightInPoints(24);for(int i=0;i<10;i++)row.createCell(i).setCellStyle(text);return row;}
  private void value(Row row,int col,String value){row.getCell(col).setCellValue(value);}
  private void amount(Row row,int col,String value){
    var cell=row.getCell(col);cell.setCellStyle(money);
    if(value==null){cell.setCellValue("历史未记录");return;}
    var number=new BigDecimal(value);
    // Retain high precision as text, just as the main saved-cost worksheet does.
    if(number.stripTrailingZeros().precision()>15)cell.setCellValue(number.stripTrailingZeros().toPlainString());
    else cell.setCellValue(number.doubleValue());
  }
  private void merged(String value,CellStyle style){Row row=row();merge(row,0,9,value,style);row.setHeightInPoints(30);}
  private void merge(Row row,int first,int last,String value,CellStyle style){if(value!=null)row.getCell(first).setCellValue(value);for(int i=first;i<=last;i++)row.getCell(i).setCellStyle(style);sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(),row.getRowNum(),first,last));}
  private CellStyle style(boolean bold,boolean fill){
    var style=workbook.createCellStyle();var font=workbook.createFont();font.setFontName("微软雅黑");font.setFontHeightInPoints((short)10);font.setBold(bold);style.setFont(font);
    style.setWrapText(true);style.setVerticalAlignment(VerticalAlignment.CENTER);style.setDataFormat(workbook.createDataFormat().getFormat("@"));
    style.setBorderBottom(BorderStyle.HAIR);style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
    if(fill){style.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);}
    return style;
  }
  private void configure(){sheet.setFitToPage(true);sheet.getPrintSetup().setLandscape(true);sheet.getPrintSetup().setPaperSize(PrintSetup.A3_PAPERSIZE);sheet.getPrintSetup().setFitWidth((short)1);sheet.getPrintSetup().setFitHeight((short)0);workbook.setPrintArea(workbook.getSheetIndex(sheet),0,9,0,next-1);sheet.getFooter().setRight("第 &P 页 / 共 &N 页");}
}
