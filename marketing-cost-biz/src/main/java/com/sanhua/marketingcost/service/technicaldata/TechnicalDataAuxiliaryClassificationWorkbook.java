package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse.Row;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** 只交换审批字段的精确文本，导入不执行公式，也不按行号匹配业务明细。 */
@Component
public class TechnicalDataAuxiliaryClassificationWorkbook {
  private static final String SHEET="辅料归类";
  private static final List<String> HEADERS=List.of("报价单号","产品行标识","核算月份","审批版本","明细标识","审批内容摘要",
      "部件名称","序号","工序名称","辅料料号","辅料名称","原不含税价格","体积/表面积","可加工数量（只）",
      "原分摊费用（元/只）","原归类","备注","已审批金额（元/只）","原文件摘要","原工作表","原表行号","二级科目名称");

  public byte[] export(AuxiliaryClassificationResponse source) {
    if (!source.canClassify() || source.items().isEmpty()) throw new IllegalArgumentException("当前没有可下载归类的审批辅料");
    try (var book=new XSSFWorkbook(); var bytes=new ByteArrayOutputStream()) {
      var sheet=book.createSheet(SHEET); write(sheet,0,HEADERS);
      for (int index=0;index<source.items().size();index++) {
        var item=source.items().get(index); var cells=new ArrayList<>(item.approvedColumns());
        cells.add(item.subjectName()==null?"":item.subjectName()); write(sheet,index+1,cells);
      }
      sheet.createFreezePane(0,1);
      for (int index=0;index<HEADERS.size();index++) sheet.setColumnWidth(index, index==5 || index==18 ? 22*256 : 18*256);
      var help=book.createSheet("科目说明");
      write(help,0,List.of("仅填写辅料归类表的二级科目名称，其余内容保持原值；允许调整明细顺序。"));
      write(help,1,List.of("二级科目编码","二级科目名称"));
      for (int index=0;index<source.subjects().size();index++) {
        var subject=source.subjects().get(index); write(help,index+2,List.of(subject.code(),subject.name()));
      }
      help.setColumnWidth(0,36*256); help.setColumnWidth(1,30*256);
      book.write(bytes); return bytes.toByteArray();
    } catch (java.io.IOException error) { throw new IllegalStateException("辅料归类表生成失败",error); }
  }

  public List<Row> parse(byte[] bytes) {
    if (bytes==null || bytes.length==0 || bytes.length>10*1024*1024) throw new IllegalArgumentException("请上传不超过 10 MB 的辅料归类 Excel");
    try (var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var sheet=book.getSheet(SHEET);
      if (sheet==null || !read(sheet.getRow(0)).equals(HEADERS)) throw new IllegalArgumentException("请使用下载的辅料归类表，不要修改表头");
      if (sheet.getLastRowNum()>5000) throw new IllegalArgumentException("辅料归类不能超过 5000 行");
      List<Row> result=new ArrayList<>();
      for (int index=1;index<=sheet.getLastRowNum();index++) {
        var values=read(sheet.getRow(index));
        if (values.stream().allMatch(String::isEmpty)) continue;
        result.add(new Row(index+1,values));
      }
      return List.copyOf(result);
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("无法读取辅料归类 Excel，请检查文件格式",error); }
  }

  private List<String> read(org.apache.poi.ss.usermodel.Row row) {
    List<String> values=new ArrayList<>();
    for (int index=0;index<HEADERS.size();index++) {
      var cell=row==null?null:row.getCell(index);
      if (cell==null || cell.getCellType()==CellType.BLANK) { values.add(""); continue; }
      if (cell.getCellType()!=CellType.STRING) throw new IllegalArgumentException("第 "+(row.getRowNum()+1)+" 行 "+HEADERS.get(index)+" 请保留下载的文本格式，不要填公式");
      values.add(cell.getStringCellValue());
    }
    if (row!=null && row.getLastCellNum()>HEADERS.size()) throw new IllegalArgumentException("归类表不能增加列");
    return List.copyOf(values);
  }
  private void write(Sheet sheet,int index,List<String> values) {
    var row=sheet.createRow(index);
    for (int column=0;column<values.size();column++) row.createCell(column,CellType.STRING).setCellValue(values.get(column));
  }
}
