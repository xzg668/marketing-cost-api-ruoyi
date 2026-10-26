package com.sanhua.marketingcost.service.pricing.household;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.PriceFact;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAttachmentStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;

/** Reads supplied financial P/H and cached formulas; never evaluates or rebuilds prices. */
@Component
public class HouseholdInquiryWorkbookParser {
  public static final String SHEET="10月线圈询价";
  public static final String SCHEMA="HOUSEHOLD_COIL_V1";
  public static final int MAX_BYTES=10*1024*1024;
  private static final int MAX_ROWS=10_000;
  private static final Set<Integer> INPUT_COLUMNS=Set.of(0,1,2,4,13);
  private static final Map<Integer,String> REQUIRED=Map.of(0,"料号",1,"名称",2,"规格",4,"报价总成本（含两项费用、不含运费）",13,"两项费用比例%");

  public record Issue(int excelRow,String column,String message) {}
  public record Duplicate(String materialCode,int excelRow,int firstExcelRow) {}
  public record CellEvidence(String cell,String type,String value,String formula,String cachedType,String numberFormat) {}
  public record ReferenceValue(String header,String type,String value,String formula,String cachedType) {}
  public record BusinessRow(String materialCode,String materialName,String specification,String unitPrice,
      String householdRate,Map<String,ReferenceValue> financialReference,Map<String,String> inputFormulas) {
    public BusinessRow { financialReference=Collections.unmodifiableMap(new LinkedHashMap<>(financialReference));inputFormulas=Map.copyOf(inputFormulas); }
  }
  public record SourceRow(List<Integer> sourceRows,String materialCode,String materialName,String specification,
      String unitPrice,String householdRate,String rateEncoding,List<CellEvidence> cells,Map<String,ReferenceValue> financialReference) {
    public SourceRow { sourceRows=List.copyOf(sourceRows);cells=List.copyOf(cells);financialReference=Collections.unmodifiableMap(new LinkedHashMap<>(financialReference)); }
    public BusinessRow businessRow() {
      var formulas=new TreeMap<String,String>();
      for (var cell:cells) if(cell.formula()!=null && (cell.cell().startsWith("E")||cell.cell().startsWith("N"))) {
        formulas.put(cell.cell().replaceAll("[0-9]", ""),cell.formula());
      }
      return new BusinessRow(materialCode,materialName,specification,unitPrice,householdRate,financialReference,formulas);
    }
    SourceRow merge(int row) { var rows=new ArrayList<>(sourceRows);rows.add(row);return new SourceRow(rows,materialCode,materialName,specification,unitPrice,householdRate,rateEncoding,cells,financialReference); }
  }
  public record WorkbookData(String fileName,String sha256,List<SourceRow> rows,List<Issue> errors,List<Duplicate> duplicates) {
    public WorkbookData { rows=List.copyOf(rows);errors=List.copyOf(errors);duplicates=List.copyOf(duplicates); }
  }

  public WorkbookData parse(byte[] bytes,String originalName) {
    String name=fileName(originalName);
    if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES) throw new IllegalArgumentException("请上传非空且不超过10 MB的家用询价工作簿");
    var rows=new LinkedHashMap<String,SourceRow>();var errors=new ArrayList<Issue>();var duplicates=new ArrayList<Duplicate>();
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var sheet=book.getSheet(SHEET);
      if(sheet==null) throw new IllegalArgumentException("缺少工作表「"+SHEET+"」");
      if(!"明细表2".equals(label(cell(sheet.getRow(0),1)))) throw new IllegalArgumentException("指定工作表第1行标题应为「明细表2」");
      for(var required:REQUIRED.entrySet()) {
        if(!required.getValue().equals(normalize(label(cell(sheet.getRow(1),required.getKey()))))) {
          throw new IllegalArgumentException("指定工作表"+CellReference.convertNumToColString(required.getKey())+"2表头应为「"+required.getValue()+"」");
        }
      }
      if(sheet.getLastRowNum()>MAX_ROWS+1) throw new IllegalArgumentException("家用询价最多支持10000行");
      for(int index=2;index<=sheet.getLastRowNum();index++) {
        var row=sheet.getRow(index);if(blank(row)) continue;
        try {
          var parsed=read(row,sheet.getRow(1));var previous=rows.putIfAbsent(parsed.materialCode(),parsed);
          if(previous!=null) {
            if(previous.businessRow().equals(parsed.businessRow())) {
              rows.put(parsed.materialCode(),previous.merge(index+1));duplicates.add(new Duplicate(parsed.materialCode(),index+1,previous.sourceRows().getFirst()));
            } else errors.add(new Issue(index+1,"A","料号与第"+previous.sourceRows().getFirst()+"行重复，但价格或参考内容不同"));
          }
        } catch(RowFailure failure) { errors.add(new Issue(index+1,failure.column,failure.getMessage())); }
      }
    } catch(IOException failure) { throw new IllegalArgumentException("无法读取家用询价工作簿",failure); }
    if(rows.isEmpty()&&errors.isEmpty()) errors.add(new Issue(3,"A","文件没有有效询价行"));
    return new WorkbookData(name,TechnicalDataAttachmentStore.sha256(bytes),List.copyOf(rows.values()),errors,duplicates);
  }

  private SourceRow read(Row row,Row header) {
    String material=material(cell(row,0));
    String name=text(cell(row,1),"B",255),specification=text(cell(row,2),"C",512);
    BigDecimal price=number(cell(row,4),"E"),rate=number(cell(row,13),"N");
    Cell rateCell=cell(row,13);String encoding;
    try {
    rate=rate.stripTrailingZeros();
    if(effectiveType(rateCell)==CellType.STRING && rateCell.getStringCellValue().strip().endsWith("%")) {
      rate=rate.scaleByPowerOfTen(-2);encoding="TEXT_PERCENT";
    } else if(effectiveType(rateCell)==CellType.NUMERIC && percentageFormat(rateCell.getCellStyle().getDataFormatString())) encoding="EXCEL_RATIO";
    else { rate=rate.scaleByPowerOfTen(-2);encoding="HEADER_PERCENTAGE_POINTS"; }
    } catch(ArithmeticException failure) { throw new RowFailure("N","两项费用率超出可保存的十进制范围"); }
    validate(price,rate);
    var cells=new ArrayList<CellEvidence>();var reference=new LinkedHashMap<String,ReferenceValue>();
    int columns=Math.max(19,Math.max(row.getLastCellNum(),header.getLastCellNum()));
    if(columns>64) throw new RowFailure("A","询价列数超过64列");
    for(int column=0;column<columns;column++) {
      Cell c=cell(row,column);String letter=CellReference.convertNumToColString(column);
      CellType type=c==null?CellType.BLANK:c.getCellType(),cached=type==CellType.FORMULA?c.getCachedFormulaResultType():null;
      String value=evidenceValue(c,type==CellType.FORMULA?cached:type);
      String formula=type==CellType.FORMULA?c.getCellFormula():null;
      cells.add(new CellEvidence(letter+(row.getRowNum()+1),type.name(),value,formula,cached==null?null:cached.name(),c==null?null:c.getCellStyle().getDataFormatString()));
      boolean emptyExtension=column>=19 && label(cell(header,column)).isBlank()
          && (type==CellType.BLANK || type==CellType.STRING && (value==null||value.isBlank()));
      if(!INPUT_COLUMNS.contains(column)&&!emptyExtension) reference.put(letter,new ReferenceValue(normalize(label(cell(header,column))),type.name(),value,formula,cached==null?null:cached.name()));
    }
    return new SourceRow(List.of(row.getRowNum()+1),material,name,specification,plain(price),plain(rate),encoding,cells,reference);
  }

  private static void validate(BigDecimal price,BigDecimal rate) {
    try { PriceFact.household(price,BigDecimal.ZERO,SourceEvidence.ofJson("{\"validation\":true}")); }
    catch(IllegalArgumentException|ArithmeticException error) { throw new RowFailure("E","报价总成本必须非负并可无损保存为24位、12小数"); }
    try { PriceFact.household(BigDecimal.ZERO,rate,SourceEvidence.ofJson("{\"validation\":true}")); }
    catch(IllegalArgumentException|ArithmeticException error) { throw new RowFailure("N","两项费用率必须非负并可无损保存为18位、12小数"); }
  }
  private static String material(Cell cell) {
    if(cell==null) throw new RowFailure("A","料号不能为空");
    String result;
    if(cell.getCellType()==CellType.STRING) result=cell.getStringCellValue().strip();
    else if(cell.getCellType()==CellType.NUMERIC) {
      BigDecimal value=number(cell,"A");
      if(value.signum()<0||value.stripTrailingZeros().scale()>0||value.precision()-value.scale()>15) throw new RowFailure("A","数值料号必须为不超过15位的精确整数");
      result=value.toBigIntegerExact().toString();String format=cell.getCellStyle().getDataFormatString();
      if(format.matches("0{1,64}")) result="0".repeat(Math.max(0,format.length()-result.length()))+result;
      else if(!"General".equalsIgnoreCase(format)&&!"@".equals(format)) throw new RowFailure("A","无法可靠还原数值料号格式，请使用文本料号");
    } else throw new RowFailure("A","料号必须为文本或精确整数，不能使用公式");
    if(result.isBlank()||result.length()>64) throw new RowFailure("A","料号不能为空或超过64字符");return result;
  }
  private static BigDecimal number(Cell cell,String column) {
    if(cell==null) throw new RowFailure(column,"价格或比例不能为空");
    CellType type=effectiveType(cell);
    if(cell.getCellType()==CellType.FORMULA && (type!=CellType.NUMERIC || cell instanceof XSSFCell x && x.getRawValue()==null)) throw new RowFailure(column,"公式必须提供合法数值缓存，本系统不执行公式");
    try {
      if(type==CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
      if(type==CellType.STRING) {
        String value=cell.getStringCellValue().strip();if(value.length()>128) throw new NumberFormatException();
        if(column.equals("N")&&value.endsWith("%")) value=value.substring(0,value.length()-1).strip();
        return new BigDecimal(value);
      }
    } catch(NumberFormatException failure) { throw new RowFailure(column,"价格或比例必须为有效十进制数"); }
    throw new RowFailure(column,"价格或比例不能为空或包含Excel错误");
  }
  private static String text(Cell cell,String column,int max) {
    if(cell==null||cell.getCellType()==CellType.BLANK) return null;
    if(cell.getCellType()!=CellType.STRING) throw new RowFailure(column,"名称和规格必须为文本");
    String result=cell.getStringCellValue().strip();if(result.length()>max) throw new RowFailure(column,"内容超过"+max+"字符");return result.isEmpty()?null:result;
  }
  private static String evidenceValue(Cell cell,CellType type) {
    if(cell==null||type==CellType.BLANK) return null;
    String value=switch(type) {
      case NUMERIC -> plain(BigDecimal.valueOf(cell.getNumericCellValue()));
      case STRING -> cell.getStringCellValue();
      case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
      case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
      default -> null;
    };
    if(value!=null&&value.length()>16000) throw new RowFailure(CellReference.convertNumToColString(cell.getColumnIndex()),"参考内容过长");return value;
  }
  private static boolean percentageFormat(String format) {
    return format!=null && format.replaceAll("\"[^\"]*\"", "").replaceAll("\\\\.", "").contains("%");
  }
  private static CellType effectiveType(Cell cell) { return cell==null?CellType.BLANK:cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType(); }
  private static Cell cell(Row row,int column) { return row==null?null:row.getCell(column); }
  private static String label(Cell cell) { return cell!=null&&cell.getCellType()==CellType.STRING?cell.getStringCellValue():""; }
  private static String normalize(String value) { return value.replaceAll("\\s+", ""); }
  private static String plain(BigDecimal value) { return value.signum()==0?"0":value.stripTrailingZeros().toPlainString(); }
  private static boolean blank(Row row) {
    if(row==null) return true;for(Cell c:row) if(c.getCellType()!=CellType.BLANK && !(c.getCellType()==CellType.STRING&&c.getStringCellValue().isBlank())) return false;return true;
  }
  private static String fileName(String name) {
    if(name==null) throw new IllegalArgumentException("文件名不能为空");String safe=name.replace('\\','/');safe=safe.substring(safe.lastIndexOf('/')+1);
    if(safe.isBlank()||safe.length()>255||!(safe.toLowerCase(Locale.ROOT).endsWith(".xls")||safe.toLowerCase(Locale.ROOT).endsWith(".xlsx"))) throw new IllegalArgumentException("请上传xls或xlsx询价工作簿");return safe;
  }
  private static class RowFailure extends IllegalArgumentException {
    final String column;RowFailure(String column,String message){super(message);this.column=column;}
  }
}
