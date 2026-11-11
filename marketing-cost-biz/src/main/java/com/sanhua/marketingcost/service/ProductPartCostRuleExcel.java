package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.service.ProductPartCostRuleService.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** The same nine-column contract is used for download and import. */
@Component
public class ProductPartCostRuleExcel {
  public static final List<String> HEADERS = List.of("成品料号", "成品名称", "成品规格", "成品型号",
      "零件料号", "零件名称", "零件型号", "零件形态属性", "零件制造费用率");
  public static final int MAX_ROWS = 5000;
  public record Parsed(List<ImportRow> rows, List<ImportError> errors) {}

  public byte[] template() throws IOException {
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("配置填写");
      var header = book.createCellStyle(); header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND); header.setWrapText(true);
      var font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); header.setFont(font);
      var text = book.createCellStyle(); text.setDataFormat(book.createDataFormat().getFormat("@"));
      var percent = book.createCellStyle(); percent.setDataFormat(book.createDataFormat().getFormat("0.00########%"));
      var row = sheet.createRow(0); row.setHeightInPoints(32);
      for (int i=0;i<HEADERS.size();i++) {
        var cell=row.createCell(i); cell.setCellValue(HEADERS.get(i)); cell.setCellStyle(header);
        sheet.setColumnWidth(i, (i==1 || i==5 ? 26 : 22)*256);
        sheet.setDefaultColumnStyle(i, i==8 ? percent : text);
      }
      sheet.createFreezePane(0,1); sheet.setAutoFilter(new CellRangeAddress(0,MAX_ROWS,0,8));
      var helper=sheet.getDataValidationHelper();
      var shape=helper.createValidation(helper.createExplicitListConstraint(new String[]{"采购件","制造件"}),new CellRangeAddressList(1,MAX_ROWS,7,7));
      shape.setEmptyCellAllowed(false); shape.setShowErrorBox(true); shape.setErrorStyle(DataValidation.ErrorStyle.STOP);
      shape.createErrorBox("形态填写错误", "请选择采购件或制造件"); sheet.addValidationData(shape);
      var rate=helper.createValidation(helper.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN,"0","0.999999999999"),new CellRangeAddressList(1,MAX_ROWS,8,8));
      rate.setEmptyCellAllowed(false); rate.setShowErrorBox(true); rate.setShowPromptBox(true); rate.setErrorStyle(DataValidation.ErrorStyle.STOP);
      rate.createPromptBox("按百分比填写", "例如输入10%，允许0%，必须小于100%");
      rate.createErrorBox("费用率填写错误", "请输入0%至小于100%的百分比，例如10%"); sheet.addValidationData(rate);
      var notes=book.createSheet("填写说明"); for(int i=0;i<9;i++)notes.setColumnWidth(i,22*256);
      var wrap=book.createCellStyle(); wrap.setWrapText(true); wrap.setVerticalAlignment(VerticalAlignment.CENTER);
      String[][] instructions={
        {"填写位置", "只填写“配置填写”工作表，从第2行开始；保留第1行九个表头及顺序，勿合并单元格。"},
        {"必填项目", "成品料号、零件形态属性、零件制造费用率必填；零件料号和零件型号至少填写一项。"},
        {"料号及型号格式", "料号和型号列已设为文本，保留前导零。复制时请粘贴为文本，勿转换成数字或科学计数法。"},
        {"形态选项", "从下拉框选择采购件或制造件。"},
        {"费用率格式", "按百分比填写，例如10%或0%。允许0%，必须小于100%；不要用数字10表示10%。最多支持10位百分比小数。"},
        {"匹配规则", "在同一成品内先匹配零件料号，未命中再匹配型号；型号相同但不同料号的配置可以共存。"},
        {"导入规则", "每份最多5000行、10MB，仅支持.xlsx。导入只新增；已有配置请在列表编辑。任意错误整份不导入，按提示行号修改后重试。"},
        {"业务单元", "配置归属当前登录业务单元，导入后不自动核算、不修改历史成本。"},
        {"示例（仅供参考）", "以下示例不参与导入，请在“配置填写”中填写自己的实际数据。"}};
      for(int i=0;i<instructions.length;i++) { var n=notes.createRow(i); n.setHeightInPoints(40);
        for(int j=0;j<2;j++) {var c=n.createCell(j==0?0:2);c.setCellValue(instructions[i][j]);c.setCellStyle(wrap);}
        notes.addMergedRegion(new CellRangeAddress(i,i,0,1));notes.addMergedRegion(new CellRangeAddress(i,i,2,8)); }
      var exampleHeader=notes.createRow(10);exampleHeader.setHeightInPoints(32);
      for(int i=0;i<HEADERS.size();i++){var c=exampleHeader.createCell(i);c.setCellValue(HEADERS.get(i));c.setCellStyle(header);}
      String[] example={"00100001","示例成品","示例规格","FP-A","00001234","示例零件","LJ-A","采购件"};
      var exampleRow=notes.createRow(11); for(int i=0;i<example.length;i++)exampleRow.createCell(i).setCellValue(example[i]);
      exampleRow.createCell(8).setCellValue(0.1); exampleRow.getCell(8).setCellStyle(percent);
      book.setActiveSheet(0); book.write(output); return output.toByteArray();
    }
  }

  public Parsed parse(InputStream input) throws IOException {
    try (var book = new XSSFWorkbook(input)) {
      var errors = new ArrayList<ImportError>(); var rows = new ArrayList<ImportRow>();
      var sheet=book.getSheet("配置填写");
      if(sheet==null) return invalid("缺少“配置填写”工作表，请下载最新模板");
      var header=sheet.getRow(0);
      if(header==null || header.getLastCellNum()!=9) return invalid("第1行必须保留模板的九个表头，请下载最新模板");
      for(int i=0;i<9;i++) if(!HEADERS.get(i).equals(string(header.getCell(i))))return invalid("第1行第"+(i+1)+"列表头应为“"+HEADERS.get(i)+"”，请勿修改模板表头");
      if(sheet.getLastRowNum()>MAX_ROWS) return invalid("每份最多支持5000行，请拆分文件");
      if(sheet.getNumMergedRegions()>0) return invalid("配置填写工作表不能有合并单元格");
      for(int r=1;r<=sheet.getLastRowNum();r++) {
        var row=sheet.getRow(r); if(row==null || blank(row))continue;
        try {
          if(row.getLastCellNum()>9)for(int c=9;c<row.getLastCellNum();c++)if(!empty(row.getCell(c)))throw new IllegalArgumentException("九个模板字段以外存在内容，请删除多余列内容");
          String[] values=new String[8];
          for(int c=0;c<8;c++) {
            var cell=row.getCell(c);
            if((c==0||c==3||c==4||c==6) && !empty(cell) && cell.getCellType()!=CellType.STRING)
              throw new IllegalArgumentException(HEADERS.get(c)+"须为文本，避免前导零丢失，请重新以文本填写");
            values[c]=string(cell);
          }
          BigDecimal rate=rate(row.getCell(8));
          rows.add(new ImportRow(r+1,new Input(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],rate,null)));
        } catch(IllegalArgumentException invalid) { errors.add(new ImportError(r+1,invalid.getMessage())); }
      }
      if(rows.isEmpty()&&errors.isEmpty())errors.add(new ImportError(0,"请从第2行开始填写至少一行配置"));
      return new Parsed(List.copyOf(rows),List.copyOf(errors));
    } catch(IOException | org.apache.poi.ooxml.POIXMLException | org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException invalid) {
      throw new IllegalArgumentException("文件不是有效的.xlsx，请下载模板填写后导入",invalid);
    }
  }
  private static Parsed invalid(String message){return new Parsed(List.of(),List.of(new ImportError(1,message)));}
  private static boolean empty(Cell c){return c==null||c.getCellType()==CellType.BLANK||c.getCellType()==CellType.STRING&&c.getStringCellValue().isBlank();}
  private static boolean blank(Row row){for(var c:row)if(!empty(c))return false;return true;}
  private static String string(Cell c){
    if(empty(c))return "";
    return switch(c.getCellType()){
      case STRING -> c.getStringCellValue().trim();
      case NUMERIC -> {if(DateUtil.isCellDateFormatted(c))throw new IllegalArgumentException("请填写文本，不能填写日期");yield new BigDecimal(c.toString()).stripTrailingZeros().toPlainString();}
      default -> throw new IllegalArgumentException("不能使用公式、错误值或布尔值，请粘贴为实际文本或数字");
    };
  }
  private static BigDecimal rate(Cell c){
    String value=string(c); if(value.isBlank())throw new IllegalArgumentException("零件制造费用率必填，例如10%或0%");
    try {
      boolean percent=value.endsWith("%")||value.endsWith("％");
      String raw=percent?value.substring(0,value.length()-1).trim():value;
      if(!raw.matches("[0-9]+(?:\\.[0-9]+)?"))throw new NumberFormatException();
      var rate=new BigDecimal(raw);return percent?rate.movePointLeft(2):rate;
    }catch(NumberFormatException invalid){throw new IllegalArgumentException("零件制造费用率格式错误，请填写百分比，例如10%");}
  }
}
