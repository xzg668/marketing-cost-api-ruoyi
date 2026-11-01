package com.sanhua.marketingcost.service.pricing.invoice;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.springframework.stereotype.Component;

/** Reads named columns, never a hard-coded worksheet name or material number. */
@Component
public final class InvoiceWorkbookParser {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final int MAX_ROWS = 20000;
  public record Price(String materialCode, String documentNo, String supplierCode, String supplierName,
      String materialName, String specification, String unit,
      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal unitPrice, String currency,
      String voucherNo, String businessType, String documentType, String priceType, String sheetName, int sourceRow) {
    public String identity() { return materialCode + "\u0000" + documentNo + "\u0000" + supplierCode; }
  }
  public record Parsed(List<Price> rows, int sourceRows, int duplicateCount, List<String> sheets) {
    public Parsed { rows = List.copyOf(rows); sheets = List.copyOf(sheets); }
  }
  private static final Map<String,String> HEADERS = Map.ofEntries(
      Map.entry("单据编号", "documentNo"), Map.entry("立账供应商供应商编码", "supplierCode"),
      Map.entry("立账供应商", "supplierName"), Map.entry("应付单表行料品料号", "materialCode"),
      Map.entry("应付单表行料品料品名称", "materialName"), Map.entry("应付单表行料品料品规格", "specification"),
      Map.entry("计量单位", "unit"), Map.entry("应付单表行未税单价", "unitPrice"), Map.entry("币种", "currency"),
      Map.entry("凭证编号", "voucherNo"), Map.entry("来源业务类型", "businessType"),
      Map.entry("单据类型", "documentType"), Map.entry("价格类型", "priceType"));
  private static final Set<String> REQUIRED = Set.of("documentNo", "supplierCode", "materialCode", "unit", "unitPrice", "currency");

  public Parsed parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IllegalArgumentException("请上传非空且不超过10 MB的发票工作簿");
    var prices = new LinkedHashMap<String,Price>(); var sheets = new ArrayList<String>();
    int count = 0;
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      for (var sheet : workbook) {
        Map<String,Integer> columns = null; int header = -1;
        for (int row = 0; row <= Math.min(sheet.getLastRowNum(), 19); row++) {
          var candidate = headers(sheet.getRow(row));
          if (candidate.keySet().containsAll(REQUIRED)) { columns = candidate; header = row; break; }
        }
        if (columns == null) continue;
        sheets.add(sheet.getSheetName());
        for (int number = header + 1; number <= sheet.getLastRowNum(); number++) {
          var row = sheet.getRow(number); if (row == null || blank(row, columns.values())) continue;
          if (++count > MAX_ROWS) throw new IllegalArgumentException("单次导入最多20000行");
          try {
            var fields = new HashMap<String,String>();
            for (var column : columns.entrySet()) fields.put(column.getKey(), text(row.getCell(column.getValue())));
            for (String required : REQUIRED) if (fields.getOrDefault(required, "").isBlank())
              throw new IllegalArgumentException("缺少" + label(required));
            BigDecimal amount;
            try { amount = new BigDecimal(fields.get("unitPrice")).stripTrailingZeros(); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("未税单价必须为数字"); }
            if (amount.signum() < 0 || Math.max(0, amount.scale()) > 18 || amount.precision() - amount.scale() > 20)
              throw new IllegalArgumentException("未税单价不能为负数，最多20位整数、18位小数");
            var price = new Price(field(fields,"materialCode",64), field(fields,"documentNo",128),
                field(fields,"supplierCode",64), field(fields,"supplierName",255), field(fields,"materialName",255),
                field(fields,"specification",512), field(fields,"unit",32), amount,
                currency(field(fields,"currency",16)), field(fields,"voucherNo",128), field(fields,"businessType",128),
                field(fields,"documentType",64), field(fields,"priceType",64), sheet.getSheetName(), number + 1);
            var previous = prices.get(price.identity());
            if (previous != null && (!previous.unit().equals(price.unit()) || !previous.currency().equals(price.currency())))
              throw new IllegalArgumentException("同一发票身份存在不同单位或币种，无法比较价格");
            if (previous == null || price.unitPrice().compareTo(previous.unitPrice()) > 0) prices.put(price.identity(), price);
          } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(sheet.getSheetName() + " 第" + (number + 1) + "行：" + invalid.getMessage(), invalid);
          }
        }
      }
    } catch (java.io.IOException invalid) { throw new IllegalArgumentException("无法读取发票工作簿", invalid); }
    if (sheets.isEmpty()) throw new IllegalArgumentException("未找到包含单据编号、供应商编码、料号、计量单位、未税单价、币种的表头");
    if (prices.isEmpty()) throw new IllegalArgumentException("工作簿没有发票价格数据");
    return new Parsed(new ArrayList<>(prices.values()), count, count - prices.size(), sheets);
  }
  private static Map<String,Integer> headers(Row row) {
    var result = new HashMap<String,Integer>(); if (row == null) return result;
    for (var cell : row) {
      if (cell.getCellType() != CellType.STRING) continue;
      String key = HEADERS.get(cell.getStringCellValue().replaceAll("[\\s\\p{Z}.．]", ""));
      if (key != null && result.putIfAbsent(key, cell.getColumnIndex()) != null)
        throw new IllegalArgumentException("表头重复：" + label(key));
    }
    return result;
  }
  private static boolean blank(Row row, Collection<Integer> columns) {
    return columns.stream().allMatch(index -> { var cell = row.getCell(index);
      return cell == null || cell.getCellType() == CellType.BLANK || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank(); });
  }
  private static String text(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    return switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue().strip();
      case NUMERIC -> NumberToTextConverter.toText(cell.getNumericCellValue());
      default -> throw new IllegalArgumentException("请使用文本或数值，不能包含公式、错误或布尔值");
    };
  }
  private static String field(Map<String,String> fields, String key, int maximum) {
    String value = fields.getOrDefault(key, "");
    if (value.length() > maximum || value.chars().anyMatch(c -> c == 0)) throw new IllegalArgumentException(label(key) + "过长或含无效字符");
    return value;
  }
  private static String label(String key) { return HEADERS.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey).findFirst().orElse(key); }
  private static String currency(String value) {
    return switch (value.toUpperCase(Locale.ROOT)) {
      case "人民币", "RMB", "CNY" -> "CNY";
      case "美元", "USD" -> "USD";
      case "欧元", "EUR" -> "EUR";
      default -> { if (!value.matches("[A-Z]{3}")) throw new IllegalArgumentException("无法识别币种：" + value); yield value; }
    };
  }
}
