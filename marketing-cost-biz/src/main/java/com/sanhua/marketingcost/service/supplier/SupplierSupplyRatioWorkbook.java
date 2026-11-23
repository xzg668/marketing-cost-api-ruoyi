package com.sanhua.marketingcost.service.supplier;

import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** 所有供货比率导出共用的填写格式；数据范围由各入口决定。 */
public final class SupplierSupplyRatioWorkbook {
  public static final String SHEET_NAME = "供货比率";
  public static final String SUPPLIER_PRICE_HEADER = "供应商候选单价";
  public static final List<String> HEADERS = List.of("物料代码", "物料名称", "规格", "型号", "单位",
      "物料形态属性", "供应商代码", "供应商名称", "供货比例", "说明", "取价规则");
  public static final List<String> NOTES = List.of(
      "按料号整组更新：导入同一料号时请保留该料号完整供应商集合。文件未出现的料号保持原值。",
      "供货比例填写实际百分比，如20%；0%表示退出供货，空白表示未填写。",
      "没有供货比率时，可在“供货比例”或“取价规则”列填写“无供货比率，取最高价”。",
      "规格与型号分别填写，型号未维护时留空；说明列仅供核对，不参与导入。");

  public record Entry(String materialCode, String materialName, String specification, String model,
      String unit, String materialShape, String supplierCode, String supplierName, BigDecimal ratio,
      String explanation, String selectionRule) {}

  private final Sheet sheet;
  private final CellStyle textStyle, ratioStyle, priceStyle;
  private final boolean includeSupplierPrice;

  public SupplierSupplyRatioWorkbook(Workbook book) {
    this(book, false);
  }

  public SupplierSupplyRatioWorkbook(Workbook book, boolean includeSupplierPrice) {
    this.includeSupplierPrice = includeSupplierPrice;
    sheet = book.createSheet(SHEET_NAME);
    var headerStyle = book.createCellStyle();
    headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
    headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    var font = book.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
    headerStyle.setFont(font);
    var header = sheet.createRow(0); header.setHeightInPoints(28);
    for (int i = 0; i < HEADERS.size(); i++) {
      var cell = header.createCell(i); cell.setCellValue(HEADERS.get(i)); cell.setCellStyle(headerStyle);
    }
    if (includeSupplierPrice) {
      var priceHeader = header.createCell(HEADERS.size());
      priceHeader.setCellValue(SUPPLIER_PRICE_HEADER);
      priceHeader.setCellStyle(headerStyle);
      sheet.setColumnWidth(HEADERS.size(), 53 * 256);
    }
    textStyle = book.createCellStyle(); textStyle.setWrapText(true);
    textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
    ratioStyle = book.createCellStyle();
    ratioStyle.setDataFormat(book.createDataFormat().getFormat("0.0000%"));
    ratioStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
    ratioStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    priceStyle = includeSupplierPrice ? book.createCellStyle() : null;
    if (priceStyle != null) priceStyle.setDataFormat(book.createDataFormat().getFormat("0.00############"));
    var comment = sheet.createDrawingPatriarch().createCellComment(book.getCreationHelper().createClientAnchor());
    comment.setAuthor("供货比率填写说明");
    comment.setString(book.getCreationHelper().createRichTextString(String.join("\n", NOTES)));
    header.getCell(8).setCellComment(comment);
    int[] widths = {21, 30, 48, 48, 12, 20, 20, 38, 18, 50, 32};
    for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
    sheet.createFreezePane(1, 1);
  }

  public void append(Entry entry) {
    append(entry, null, null);
  }

  public void append(Entry entry, BigDecimal supplierPrice, String priceDetails) {
    var row = sheet.createRow(sheet.getLastRowNum() + 1); row.setHeightInPoints(32);
    String[] values = {entry.materialCode(), entry.materialName(), entry.specification(), entry.model(),
        entry.unit(), entry.materialShape(), entry.supplierCode(), entry.supplierName(), null,
        entry.explanation(), entry.selectionRule()};
    for (int i = 0; i < values.length; i++) {
      var cell = row.createCell(i); cell.setCellStyle(i == 8 ? ratioStyle : textStyle);
      if (values[i] != null) cell.setCellValue(values[i]);
    }
    if (entry.ratio() != null) row.getCell(8).setCellValue(entry.ratio().doubleValue());
    if (includeSupplierPrice) {
      var priceCell = row.createCell(HEADERS.size());
      if (supplierPrice != null) {
        priceCell.setCellStyle(priceStyle);
        priceCell.setCellValue(supplierPrice.doubleValue());
      } else if (priceDetails != null) {
        priceCell.setCellStyle(textStyle);
        priceCell.setCellValue(priceDetails);
        row.setHeightInPoints(Math.min(409, Math.max(32, priceDetails.lines().count() * 30)));
      }
    }
  }

  public void finish() {
    sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0,
        includeSupplierPrice ? HEADERS.size() : HEADERS.size() - 1));
  }

  public static void addInstructions(Workbook book) {
    addInstructions(book, false);
  }

  public static void addInstructions(Workbook book, boolean includeSupplierPrice) {
    var help = book.createSheet("填写说明");
    String[][] rows = {
        {"说明", "供应商供货比例"},
        {"用途", "在第一个工作表填写实际数据后保存，再通过供货比率页面导入。"},
        {"编码", "料号、型号、供应商编码按文本保存，请保留前导零。"}};
    for (var values : rows) helpRow(help, values);
    for (var note : NOTES) helpRow(help, new String[]{"填写要求", note});
    helpRow(help, new String[]{"字段", "填写说明", "示例（仅供参考）"});
    for (int i = 0; i < HEADERS.size(); i++) {
      String detail = switch (i) {
        case 8 -> "20%表示二成；0%表示退出，空白表示未填写";
        case 9 -> "仅供核对，不参与导入";
        case 10 -> "可填写“无供货比率，取最高价”";
        default -> "";
      };
      helpRow(help, new String[]{HEADERS.get(i), detail, i == 8 ? "20%" : ""});
    }
    if (includeSupplierPrice) helpRow(help, new String[]{SUPPLIER_PRICE_HEADER,
        "按本单计价条件显示该供应商的候选单价。多种价按产品和取价条件列出，未取得候选价留空。本列仅供核对，导入时忽略。", ""});
    help.setColumnWidth(0, 30 * 256); help.setColumnWidth(1, 95 * 256); help.setColumnWidth(2, 38 * 256);
  }

  private static void helpRow(Sheet sheet, String[] values) {
    var row = sheet.createRow(sheet.getPhysicalNumberOfRows());
    for (int i = 0; i < values.length; i++) row.createCell(i).setCellValue(values[i]);
  }
}
