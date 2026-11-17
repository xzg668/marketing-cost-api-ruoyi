package com.sanhua.marketingcost.service.technicaldata;

import static com.sanhua.marketingcost.service.technicaldata.TechnicalBomRows.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Only BOM填写 is imported. A real reference is supplied by the application for the example sheet. */
@Component
public class TechnicalBomWorkbook {
  public static final String ENTRY = "BOM填写";
  public static final String EXAMPLE = "填写示例";
  public static final int MAX_BYTES = 5 * 1024 * 1024;

  public byte[] template(Product reference, List<TechnicalBomRows.Row> example) {
    return template(reference, example, null);
  }

  public byte[] template(Product reference, List<TechnicalBomRows.Row> example, TechnicalBomContent.Reference source) {
    var errors = validate(reference, example, source != null);
    if (!errors.isEmpty()) throw new IllegalArgumentException("成品模板示例尚未核实：" + errors.getFirst().message());
    try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
      var textStyle = book.createCellStyle(); textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
      var titleStyle = book.createCellStyle();
      titleStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex()); titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      var font = book.createFont(); font.setBold(true); titleStyle.setFont(font);
      var sample = book.createSheet(EXAMPLE); var entry = book.createSheet(ENTRY);
      for (var sheet : List.of(sample, entry)) {
        var header = sheet.createRow(0);
        for (int col = 0; col < HEADERS.size(); col++) {
          var cell = header.createCell(col, CellType.STRING); cell.setCellValue(HEADERS.get(col)); cell.setCellStyle(titleStyle);
          sheet.setColumnWidth(col, (col == 3 || col == 8 ? 26 : 18) * 256);
          sheet.setDefaultColumnStyle(col, textStyle);
        }
        sheet.createFreezePane(5, 1);
      }
      for (int index = 0; index < example.size(); index++) {
        var row = sample.createRow(index + 1);
        var values = columns(reference, example.get(index));
        for (int col = 0; col < values.size(); col++) row.createCell(col, CellType.STRING).setCellValue(values.get(col));
      }
      var helper = entry.getDataValidationHelper();
      var validation = helper.createValidation(helper.createExplicitListConstraint(new String[]{"采购件", "制造件", "委外加工件"}), new CellRangeAddressList(1, MAX_ROWS, 11, 11));
      validation.setShowErrorBox(true); validation.createErrorBox("子件属性", "请选择采购件、制造件或委外加工件"); entry.addValidationData(validation);
      var comment = book.getCreationHelper().createRichTextString("层级按文本填写，例如1、1.1、1.10；用量相对直接母件每1件。填写示例不参与导入。请在本表填写自己产品的完整BOM，辅料和焊料也列为子件。没有产品料号可以留空。");
      var anchor = book.getCreationHelper().createClientAnchor(); anchor.setCol1(4); anchor.setCol2(9); anchor.setRow1(1); anchor.setRow2(6);
      var note = entry.createDrawingPatriarch().createCellComment(anchor); note.setString(comment); entry.getRow(0).getCell(4).setCellComment(note);
      if (source != null) {
        var properties = book.getProperties().getCustomProperties();
        properties.addProperty("样例成品", source.productCode());
        properties.addProperty("样例组织", source.priceOrgCode() + "/" + source.materialOrgCode());
        properties.addProperty("样例用途", source.purpose());
        properties.addProperty("样例BOM版本", String.join(",", source.versions()));
        properties.addProperty("样例适用日期", source.effectiveDate().toString());
        properties.addProperty("样例来源指纹", source.fingerprint());
      }
      book.setActiveSheet(1); book.write(bytes); return bytes.toByteArray();
    } catch (java.io.IOException error) { throw new IllegalStateException("BOM填写模板生成失败", error); }
  }

  public Input parse(String fileName, byte[] bytes) {
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw new IllegalArgumentException("请上传BOM填写模板.xlsx文件");
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("BOM文件不能为空，且不能超过5MB");
    List<TechnicalBomRows.Row> rows = new ArrayList<>(); List<Issue> issues = new ArrayList<>(); Product product = null;
    try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var sheet = book.getSheet(ENTRY);
      if (sheet == null) throw new IllegalArgumentException("请填写模板中的BOM填写工作表；填写示例不参与导入");
      var header = sheet.getRow(0);
      if (header == null || header.getLastCellNum() != HEADERS.size()) throw new IllegalArgumentException("请保留模板的15列表头及顺序");
      for (int col = 0; col < HEADERS.size(); col++) if (!HEADERS.get(col).equals(text(header.getCell(col)))) throw new IllegalArgumentException("第" + (col + 1) + "列表头须为：" + HEADERS.get(col));
      if (sheet.getLastRowNum() > MAX_ROWS) throw new IllegalArgumentException("BOM子件不能超过" + MAX_ROWS + "行");
      for (int index = 1; index <= sheet.getLastRowNum(); index++) {
        var row = sheet.getRow(index); if (row == null) continue;
        boolean any = false;
        for (Cell cell : row) if (cell.getCellType() != CellType.BLANK && !(cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank())) { any = true; break; }
        if (!any) continue;
        int line = index + 1;
        try {
          if (row.getLastCellNum() > HEADERS.size()) throw new IllegalArgumentException("不能在15列之外增加数据");
          List<String> values = new ArrayList<>();
          for (int col = 0; col < HEADERS.size(); col++) {
            var cell = row.getCell(col);
            if (cell != null && List.of(0, 4, 5).contains(col) && cell.getCellType() != CellType.STRING && cell.getCellType() != CellType.BLANK)
              throw new IllegalArgumentException(HEADERS.get(col) + "须按文本填写，防止层级1.10或料号前导零丢失");
            values.add(text(cell));
          }
          var current = new Product(values.get(0), values.get(1), values.get(2), values.get(3));
          if (product == null) product = current;
          else for (int col = 0; col < 4; col++) if (!blank(values.get(col)) && !Objects.equals(values.get(col), productColumns(product).get(col)))
            throw new IllegalArgumentException(HEADERS.get(col) + "与本文件首条明细不一致；一次只上传当前产品");
          rows.add(new TechnicalBomRows.Row(line, values.get(4), values.get(5), values.get(6), values.get(7), values.get(8),
              number(values.get(9), HEADERS.get(9)), values.get(10), values.get(11), values.get(12), number(values.get(13), HEADERS.get(13)), values.get(14)));
        } catch (IllegalArgumentException error) { issues.add(new Issue(line, null, error.getMessage())); }
      }
    } catch (IllegalArgumentException error) { throw error; }
    catch (Exception error) { throw new IllegalArgumentException("无法读取BOM Excel，请检查文件格式", error); }
    issues.addAll(validate(product, rows));
    return new Input(product, rows, issues);
  }
  private static List<String> productColumns(Product product) { return Arrays.asList(product.code(), product.name(), product.model(), product.specification()); }
  private static List<String> columns(Product product, TechnicalBomRows.Row row) {
    var result = new ArrayList<>(productColumns(product));
    result.addAll(Arrays.asList(row.hierarchy(), row.materialCode(), row.name(), row.model(), row.specification(),
        decimal(row.quantity()), row.unit(), row.attribute(), row.material(), decimal(row.netWeight()), row.netWeightUnit()));
    return result.stream().map(value -> Objects.toString(value, "")).toList();
  }
  private static String decimal(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
  private static BigDecimal number(String value, String label) {
    if (blank(value)) return null;
    try {
      var amount = new BigDecimal(value);
      if (amount.precision() > 24 || amount.scale() > 12 || amount.scale() < -12) throw new NumberFormatException();
      return amount;
    } catch (NumberFormatException error) { throw new IllegalArgumentException(label + "须为有效数字，最多24位有效数字、12位小数"); }
  }
  private static String text(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) return "";
    String value = switch (cell.getCellType()) {
      case STRING -> cell.getStringCellValue().trim();
      case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
      default -> throw new IllegalArgumentException("请填写实际值，不使用公式、布尔值或错误单元格");
    };
    if (value.length() > 1000) throw new IllegalArgumentException("单元格内容不能超过1000字");
    return value;
  }
}
