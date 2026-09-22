package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryUploadResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryUploadResponse.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

/** 读取实际辅料模板第 3 行表头，以“分摊费用（元/只）”作为每产品金额。 */
@Component
public class TechnicalDataAuxiliaryUploadParser {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  private static final List<String> HEADERS = List.of("部件名称", "序号", "工序名称", "辅料料号", "辅料名称",
      "辅料价格元(不含税)", "体积/表面积", "可加工数量(只)", "分摊费用(元/只)", "归类", "备注");

  public TechnicalDataAuxiliaryUploadResponse parse(String fileName, byte[] bytes) {
    var issues = new ArrayList<Issue>(); var items = new ArrayList<Item>();
    String sheetName = null;
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("请上传辅料模板 .xlsx 文件");
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("辅料文件不能为空，且不能超过 5 MB");
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var formatter = new DataFormatter(Locale.CHINA);
      var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
      var matching = new ArrayList<Sheet>();
      for (Sheet sheet : workbook) if (headers(sheet, formatter).keySet().containsAll(HEADERS)) matching.add(sheet);
      if (matching.size() != 1) {
        issues.add(new Issue(null, 3, "表头", "请保留一张辅料明细表，第 3 行须为完整模板表头；原归类和二级科目名称不能互相替代"));
      } else {
        Sheet sheet = matching.getFirst(); sheetName = sheet.getSheetName();
        var columns = headers(sheet, formatter); Set<Integer> sequences = new HashSet<>();
        if (sheet.getLastRowNum() > 5002) throw new IllegalArgumentException("辅料明细不能超过 5000 行");
        for (int index = 3; index <= sheet.getLastRowNum(); index++) {
          Row row = sheet.getRow(index); if (row == null) continue;
          int line = index + 1;
          try {
            String materialNo = materialNo(row, columns, formatter, evaluator);
            String name = text(row, columns, "辅料名称", formatter, evaluator);
            // 模板预置空白行及合计公式不构成明细；只填了价格等内容的半行仍报错。
            if (materialNo == null && name == null && !hasDetailInput(row, columns)) continue;
            if (name == null || name.length() > 255) throw new IllegalArgumentException("辅料名称不能为空且不能超过 255 字");
            BigDecimal sequenceValue = number(row, columns, "序号", formatter, evaluator, true);
            int sequence;
            try { sequence = sequenceValue.intValueExact(); }
            catch (ArithmeticException error) { throw new IllegalArgumentException("序号必须为正整数"); }
            if (sequence <= 0 || !sequences.add(sequence)) throw new IllegalArgumentException("序号必须为不重复的正整数");
            BigDecimal amount = number(row, columns, "分摊费用(元/只)", formatter, evaluator, true);
            BigDecimal price = number(row, columns, "辅料价格元(不含税)", formatter, evaluator, false);
            BigDecimal area = number(row, columns, "体积/表面积", formatter, evaluator, false);
            BigDecimal quantity = number(row, columns, "可加工数量(只)", formatter, evaluator, false);
            if (quantity != null && quantity.signum() <= 0) throw new IllegalArgumentException("可加工数量必须大于 0");
            items.add(new Item("UPLOAD:" + sheetName + ":" + sequence, line, sequence,
                text(row, columns, "部件名称", formatter, evaluator), text(row, columns, "工序名称", formatter, evaluator),
                materialNo, name, price, area, quantity, amount,
                text(row, columns, "归类", formatter, evaluator), text(row, columns, "二级科目名称", formatter, evaluator),
                text(row, columns, "备注", formatter, evaluator)));
          } catch (IllegalArgumentException error) {
            issues.add(new Issue(sheetName, line, null, error.getMessage()));
          }
        }
        if (items.isEmpty() && issues.isEmpty()) issues.add(new Issue(sheetName, null, null, "文件中没有辅料明细，不能作为已补齐资料"));
      }
    } catch (IllegalArgumentException error) { throw error;
    } catch (Exception error) {
      throw new IllegalArgumentException("无法读取辅料 Excel，请检查文件格式、公式和工作表", error);
    }
    return new TechnicalDataAuxiliaryUploadResponse(fileName, TechnicalDataAttachmentStore.sha256(bytes), sheetName, List.copyOf(items), List.copyOf(issues));
  }

  private Map<String, Integer> headers(Sheet sheet, DataFormatter formatter) {
    var result = new LinkedHashMap<String, Integer>(); Row row = sheet.getRow(2);
    if (row != null) for (Cell cell : row) {
      String header = normalize(formatter.formatCellValue(cell));
      if (!header.isEmpty() && result.putIfAbsent(header, cell.getColumnIndex()) != null) return Map.of();
    }
    return result;
  }

  private String text(Row row, Map<String, Integer> headers, String name, DataFormatter formatter, FormulaEvaluator evaluator) {
    Integer column = headers.get(name); if (column == null) return null;
    Cell cell = row.getCell(column); if (cell == null) return null;
    String value;
    try { value = formatter.formatCellValue(cell, evaluator).trim(); }
    catch (RuntimeException error) { throw new IllegalArgumentException(name + "的公式无法计算，请在 Excel 中检查", error); }
    if (value.startsWith("#")) throw new IllegalArgumentException(name + "存在 Excel 错误：" + value);
    return value.isEmpty() ? null : value;
  }

  private String materialNo(Row row, Map<String, Integer> headers, DataFormatter formatter, FormulaEvaluator evaluator) {
    String value = text(row, headers, "辅料料号", formatter, evaluator);
    if (value == null) return null;
    var evaluated = evaluator.evaluate(row.getCell(headers.get("辅料料号")));
    if (evaluated != null && evaluated.getCellType() == CellType.NUMERIC) {
      // 数字格式只影响显示，料号不是数量；不把 Excel 的 .000 或科学计数格式存成料号。
      try {
        var code = BigDecimal.valueOf(evaluated.getNumberValue()).toBigIntegerExact();
        if (code.signum() <= 0 || code.toString().length() > 15) throw new ArithmeticException();
        value = code.toString();
      } catch (ArithmeticException exception) { throw new IllegalArgumentException("数字料号须为正整数且不超过 15 位，较长料号请按文本填写"); }
    }
    if (value.length() > 64) throw new IllegalArgumentException("辅料料号不能超过 64 字");
    return value;
  }

  private BigDecimal number(Row row, Map<String, Integer> headers, String name, DataFormatter formatter,
      FormulaEvaluator evaluator, boolean required) {
    String text = text(row, headers, name, formatter, evaluator);
    if (text == null) {
      if (required) throw new IllegalArgumentException(name + "不能为空或公式未得出有效金额");
      return null;
    }
    Cell cell = row.getCell(headers.get(name)); BigDecimal number;
    try {
      CellValue evaluated = evaluator.evaluate(cell);
      number = evaluated != null && evaluated.getCellType() == CellType.NUMERIC
          ? BigDecimal.valueOf(evaluated.getNumberValue()) : new BigDecimal(text.replace(",", ""));
    } catch (RuntimeException error) { throw new IllegalArgumentException(name + "必须是有效数字", error); }
    if (number.signum() < 0 || number.precision() - number.scale() > 12) throw new IllegalArgumentException(name + "不能小于零或超过 12 位整数");
    return number.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
  }

  private String normalize(String value) {
    return value.replaceAll("\\s+", "").replace('（', '(').replace('）', ')').replace('／', '/');
  }

  private boolean hasDetailInput(Row row, Map<String, Integer> columns) {
    for (String name : List.of("工序名称", "辅料价格元(不含税)", "体积/表面积", "可加工数量(只)")) {
      Cell cell = row.getCell(columns.get(name));
      if (cell != null && cell.getCellType() != CellType.FORMULA && cell.getCellType() != CellType.BLANK
          && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) return true;
    }
    return false;
  }
}
