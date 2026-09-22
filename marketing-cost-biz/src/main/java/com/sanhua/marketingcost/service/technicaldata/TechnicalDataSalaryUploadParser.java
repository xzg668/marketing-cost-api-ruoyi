package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryUploadResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryUploadResponse.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

/** 按工时表公式重新计算，不信任 Excel 缓存；用户确认单件工资为分，只转换一次。 */
@Component
public class TechnicalDataSalaryUploadParser {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  public static final String CALCULATION_RULE = "WORK_TIME_2026_FEN_TO_YUAN_V1";
  private static final List<String> HEADERS = List.of("零部件名称", "零部件型号", "加工设备", "工序号", "工序名称",
      "人员配置", "理论节拍", "小时工资标准", "理论班产", "单件工时", "单件工资", "备注");

  public TechnicalDataSalaryUploadResponse parse(String fileName, byte[] bytes) {
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("请上传工时模板 .xlsx 文件");
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("工时文件不能为空，且不能超过 5 MB");
    var issues = new ArrayList<Issue>();
    var items = new ArrayList<Item>();
    String sheetName = null;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var formatter = new DataFormatter(Locale.CHINA);
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      var matching = new ArrayList<Sheet>();
      for (Sheet sheet : workbook) if (headers(sheet, formatter).keySet().containsAll(HEADERS)) matching.add(sheet);
      if (matching.size() != 1) {
        issues.add(new Issue(null, 2, "请保留一张工时明细表，第 2 行须为完整模板表头"));
      } else {
        var sheet = matching.getFirst();
        sheetName = sheet.getSheetName();
        var columns = headers(sheet, formatter);
        if (sheet.getLastRowNum() > 5001) throw new IllegalArgumentException("工时明细不能超过 5000 行");
        for (int index = 2; index <= sheet.getLastRowNum(); index++) {
          var row = sheet.getRow(index);
          if (row == null || emptyDetail(row, columns, formatter)) continue;
          try {
            BigDecimal staffing = number(row, columns, "人员配置", evaluator, true);
            BigDecimal cycleTime = number(row, columns, "理论节拍", evaluator, true);
            BigDecimal hourlyWage = number(row, columns, "小时工资标准", evaluator, false);
            BigDecimal shiftOutput = number(row, columns, "理论班产", evaluator, true);
            BigDecimal unitTime = number(row, columns, "单件工时", evaluator, true);
            BigDecimal fen = number(row, columns, "单件工资", evaluator, false);
            BigDecimal yuan = fen.movePointLeft(2).setScale(8, RoundingMode.UNNECESSARY);
            items.add(new Item("UPLOAD:" + sheetName + ":" + (index + 1), index + 1,
                text(row, columns, "零部件名称", formatter, evaluator), text(row, columns, "零部件型号", formatter, evaluator),
                text(row, columns, "加工设备", formatter, evaluator), text(row, columns, "工序号", formatter, evaluator),
                text(row, columns, "工序名称", formatter, evaluator), staffing, cycleTime, hourlyWage, shiftOutput, unitTime, fen, yuan,
                formula(row, columns, "理论班产"), formula(row, columns, "单件工时"), formula(row, columns, "单件工资"),
                text(row, columns, "备注", formatter, evaluator)));
          } catch (IllegalArgumentException error) {
            issues.add(new Issue(sheetName, index + 1, error.getMessage()));
          }
        }
        if (items.isEmpty() && issues.isEmpty()) issues.add(new Issue(sheetName, null, "文件中没有工时明细，不能作为已补齐资料"));
      }
    } catch (IllegalArgumentException error) { throw error;
    } catch (Exception error) { throw new IllegalArgumentException("无法读取工时 Excel，请检查文件格式、公式和工作表", error); }
    BigDecimal totalFen = issues.isEmpty() ? items.stream().map(Item::amountFen).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
    BigDecimal totalYuan = totalFen == null ? null : totalFen.movePointLeft(2).setScale(8, RoundingMode.UNNECESSARY);
    if (totalYuan != null && totalYuan.compareTo(new BigDecimal("1000000000000")) >= 0) throw new IllegalArgumentException("工时工资合计超出可保存范围");
    return new TechnicalDataSalaryUploadResponse(fileName, TechnicalDataAttachmentStore.sha256(bytes), sheetName,
        CALCULATION_RULE, items, totalFen, totalYuan, issues);
  }

  private Map<String, Integer> headers(Sheet sheet, DataFormatter formatter) {
    var result = new LinkedHashMap<String, Integer>();
    var row = sheet.getRow(1);
    if (row != null) for (Cell cell : row) {
      String name = formatter.formatCellValue(cell).replaceAll("\\s+", "").trim();
      if (!name.isEmpty() && result.putIfAbsent(name, cell.getColumnIndex()) != null) return Map.of();
    }
    return result;
  }

  private boolean emptyDetail(Row row, Map<String, Integer> columns, DataFormatter formatter) {
    boolean hasInput = false;
    boolean total = false;
    for (String header : HEADERS.subList(0, 8)) {
      String value = formatter.formatCellValue(row.getCell(columns.get(header))).trim();
      if (List.of("合计", "总计", "小计").contains(value)) total = true;
      else if (!value.isEmpty()) hasInput = true;
    }
    // 空白预留行的 I/J/K 公式可能除零，不能先计算它们；有实际输入的半行仍报错。
    if (total && !hasInput) return true;
    if (hasInput || !formatter.formatCellValue(row.getCell(columns.get("备注"))).isBlank()) return false;
    for (String header : List.of("理论班产", "单件工时", "单件工资")) {
      Cell cell = row.getCell(columns.get(header));
      if (cell != null && cell.getCellType() != CellType.FORMULA && !formatter.formatCellValue(cell).isBlank()) return false;
    }
    return true;
  }

  private BigDecimal number(Row row, Map<String, Integer> columns, String name, FormulaEvaluator evaluator, boolean positive) {
    Cell cell = row.getCell(columns.get(name));
    CellValue value;
    try { value = cell == null ? null : evaluator.evaluate(cell); }
    catch (RuntimeException error) { throw new IllegalArgumentException(name + "公式无法计算，请检查 Excel", error); }
    if (value == null || value.getCellType() == CellType.BLANK) throw new IllegalArgumentException(name + "不能为空");
    if (value.getCellType() == CellType.ERROR) throw new IllegalArgumentException(name + "存在 Excel 错误：" + FormulaError.forInt(value.getErrorValue()).getString());
    BigDecimal amount;
    try {
      amount = value.getCellType() == CellType.NUMERIC ? BigDecimal.valueOf(value.getNumberValue())
          : value.getCellType() == CellType.STRING ? new BigDecimal(value.getStringValue().trim()) : null;
      if (amount == null) throw new NumberFormatException();
    } catch (RuntimeException error) { throw new IllegalArgumentException(name + "必须为有效数字"); }
    if (amount.signum() < 0 || positive && amount.signum() == 0) throw new IllegalArgumentException(name + (positive ? "必须大于 0" : "不能小于 0"));
    if (amount.compareTo(new BigDecimal("1000000000000")) >= 0) throw new IllegalArgumentException(name + "数值超出范围");
    if ("单件工资".equals(name)) {
      // 六位分金额对应八位元金额；超出存储精度时明确报错，不能悄悄舍入报价。
      try { amount = amount.setScale(6, RoundingMode.UNNECESSARY); }
      catch (ArithmeticException error) { throw new IllegalArgumentException("单件工资（分）最多保留 6 位小数，请检查模板公式"); }
    }
    return amount.stripTrailingZeros();
  }

  private String text(Row row, Map<String, Integer> columns, String name, DataFormatter formatter, FormulaEvaluator evaluator) {
    String value;
    try { value = formatter.formatCellValue(row.getCell(columns.get(name)), evaluator).trim(); }
    catch (RuntimeException error) { throw new IllegalArgumentException(name + "公式无法计算", error); }
    if (value.startsWith("#")) throw new IllegalArgumentException(name + "存在 Excel 错误：" + value);
    if (value.length() > 1000) throw new IllegalArgumentException(name + "内容过长");
    return value.isEmpty() ? null : value;
  }

  private String formula(Row row, Map<String, Integer> columns, String name) {
    Cell cell = row.getCell(columns.get(name));
    return cell != null && cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null;
  }
}
