package com.sanhua.marketingcost.service.jjb;

import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** One sheet, using the finance reference's actual formatting and saved monetary values. */
@Component
public class JjbExcelRenderer {
  private static final String MONEY = "0.000000", DECIMAL = "0.########################",
      PERCENT = "0.########################%";
  private static final String[] COLUMNS = {
    "子件编码", "部品名", "部品图号", "部品单价", "部品用量", "部品价格", "毛重", "净重",
    "原材料\n单价", "废料\n单价", "材质", "形态属性", "价格来源", "备注\n（手填）", "备注-CMS\n来源信息"
  };

  public int write(JjbReadModel model, OutputStream output) throws IOException {
    Objects.requireNonNull(model, "保存的见机表不能为空");
    try (var input = JjbExcelRenderer.class.getResourceAsStream("/templates/jianji-v1-layout.xlsx")) {
      if (input == null) throw new IOException("见机表版式模板缺失");
      try (var workbook = new XSSFWorkbook(input)) {
        var layout = new Layout(workbook);
        layout.header(model);
        for (int i = 0; i < model.rows().size(); ) {
          String section = model.rows().get(i).section();
          int start = i;
          while (i < model.rows().size() && Objects.equals(section, model.rows().get(i).section())) i++;
          layout.group(section, model.rows().subList(start, i));
        }
        layout.totals(model.totals());
        layout.note("成本版本：" + text(model.identity().versionNo()) + "；核算月份："
            + text(model.identity().pricingMonth()) + "；版本财务Cu：" + text(model.header().financeCuPrice())
            + " " + text(model.header().copperUnit()) + "。金额为元/件；完整保存依据见单元格批注。"
            + text(model.header().descriptionBasis()));
        for (String warning : model.warnings()) layout.note(warning);
        layout.comment(layout.sheet.getRow(0).getCell(0), model.identity().toString()
            + "\n月度价格依据：" + model.monthlyPrices());
        layout.configurePrint();
        workbook.getProperties().getCoreProperties().setTitle("产品成本计算一览表 - " + model.identity().costRunNo());
        workbook.getProperties().getCoreProperties().setDescription("保存成本版本 " + model.identity().versionId());
        workbook.write(output);
      }
    }
    return model.rows().size();
  }

  private static final class Layout {
    final XSSFWorkbook wb;
    final Sheet sheet;
    final CellStyle[][] template = new CellStyle[75][15];
    final float[] heights = new float[75];
    final Map<String, CellStyle> formats = new HashMap<>();
    final Drawing<?> drawing;
    int next;
    float pageHeight;

    Layout(XSSFWorkbook wb) {
      this.wb = wb;
      sheet = wb.getSheetAt(0);
      for (int r = 0; r < 75; r++) {
        Row row = sheet.getRow(r);
        heights[r] = row.getHeightInPoints();
        for (int c = 0; c < 15; c++) template[r][c] = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getCellStyle();
      }
      for (int r = sheet.getLastRowNum(); r >= 0; r--) sheet.removeRow(sheet.getRow(r));
      while (sheet.getNumMergedRegions() > 0) sheet.removeMergedRegion(0);
      drawing = sheet.createDrawingPatriarch();
    }

    void header(JjbReadModel m) {
      Row title = create(0);
      merge(title, 0, 14, "产品成本计算一览表");
      String[][] labels = {
        {"OA单号", null, null, "报价员", null, "申请部门", null, "申请处室", null, "不锈钢-316L\n（元/吨）", null, "电解铜\n（元/吨）", null, "电解铝\n（元/吨）", null},
        {"客户名称", null, null, "客户编号", null, "是否通过海外\n仓库发终端客户", null, "销售价格\n是否联动", null, "不锈钢-304\n（元/吨）", null, "电解锌\n（元/吨）", null, "/", null},
        {"父件名称", null, null, "父件型号", null, "业务类型", null, "事业部", null, "美国柜装黄铜", null, "H65黄铜边料", null, "/", null},
        {"一级编码", null, null, "父件规格", null, "客户图号", null, "客户Cu\n（元/公斤）", null, "白银", null, "黄金", null, "/", null}
      };
      var h = m.header();
      for (int r = 1; r <= 4; r++) {
        Row row = create(r);
        for (int c = 0; c < 15; c++) string(row, c, labels[r - 1][c]);
        merge(row, 1, 2, switch (r) {
          case 1 -> m.identity().oaNo(); case 2 -> h.customerName();
          case 3 -> h.productName(); default -> m.identity().productCode();
        });
      }
      set(1, 4, h.quotation().get("applicantName"));
      set(1, 6, h.quotation().get("applicantDept"));
      set(1, 8, h.quotation().get("applicantOffice"));
      set(2, 4, h.customerCode());
      set(2, 6, h.quotation().get("overseasSalesMode"));
      set(2, 8, h.quotation().get("priceLinkMode"));
      set(3, 4, h.productModel());
      set(3, 6, h.quotation().get("businessType"));
      set(3, 8, h.quotation().get("division"));
      set(4, 4, h.quotation().get("spec"));
      set(4, 6, h.customerDrawing());
      numeric(sheet.getRow(1), 10, h.quotation().get("sus316lPrice"), MONEY);
      numeric(sheet.getRow(1), 12, h.quotation().get("copperPrice"), MONEY);
      numeric(sheet.getRow(1), 14, h.quotation().get("aluminumPrice"), MONEY);
      numeric(sheet.getRow(2), 10, h.quotation().get("sus304Price"), MONEY);
      numeric(sheet.getRow(2), 12, h.quotation().get("zincPrice"), MONEY);
      numeric(sheet.getRow(4), 10, h.quotation().get("silverPrice"), MONEY);
      numeric(sheet.getRow(4), 12, h.quotation().get("goldPrice"), MONEY);
      numeric(sheet.getRow(4), 8, h.quotation().get("customerCuPrice"), MONEY);
      comment(sheet.getRow(1).getCell(12), "报价需求单电解铜；单位：元/吨");
      comment(sheet.getRow(4).getCell(8), "报价需求单铜价（元/吨）÷1000；单位：元/公斤；仅展示，不重算成本");
      for (int r = 1; r <= 4; r++)
        for (int c : new int[] {1, 4, 6, 8, 10, 12, 14})
          headerFont(sheet.getRow(r).getCell(c), false);
      headerFont(sheet.getRow(4).getCell(5), true);
      headerFont(sheet.getRow(4).getCell(7), true);
      Row columns = create(5);
      for (int c = 0; c < 15; c++) string(columns, c, COLUMNS[c]);
      sheet.createFreezePane(3, 6);
      sheet.setRepeatingRows(new CellRangeAddress(0, 5, -1, -1));
      pageHeight = 0;
    }

    void group(String section, List<JjbReadModel.Row> rows) {
      int first = next;
      if (List.of("THREE_EXPENSES", "PRODUCT_FEES").contains(section)) reserve(rows.size() * 18);
      for (var row : rows) detail(row);
      if (List.of("THREE_EXPENSES", "PRODUCT_FEES").contains(section)) {
        string(sheet.getRow(first), 0, "THREE_EXPENSES".equals(section) ? "三项费用" : "新品费用");
        if (next > first + 1) sheet.addMergedRegion(new CellRangeAddress(first, next - 1, 0, 0));
      }
    }

    void detail(JjbReadModel.Row v) {
      int prototype = prototype(v);
      boolean multilineCode = "EXPLANATION".equals(v.kind()) && text(v.code()).contains("\n");
      float height = multilineCode ? Math.max(heights[prototype], (text(v.code()).split("\n", -1).length * 13) + 4) : heights[prototype];
      reserve(height);
      Row row = create(prototype);
      row.setHeightInPoints(height);
      boolean part = List.of("PART", "HOUSEHOLD", "EXPLANATION").contains(v.kind());
      boolean grouped = List.of("THREE_EXPENSES", "PRODUCT_FEES").contains(v.section());
      if (part) {
        string(row, 0, v.code()); string(row, 1, v.name()); string(row, 2, v.drawingNo());
        if (multilineCode) row.getCell(0).setCellStyle(formatted(row.getCell(0).getCellStyle(), "@", false));
      } else if ("PROPERTY".equals(v.kind())) {
        string(row, 0, "产品属性");
        string(row, 2, "产品属性".equals(v.name()) ? v.remark() : v.name());
      } else if ("MANUFACTURE_COST".equals(v.code())) merge(row, 0, 2, v.name());
      else string(row, grouped || "SUBTOTAL".equals(v.kind()) ? 2 : 1, v.name());
      numeric(row, 3, v.unitPrice(), MONEY);
      boolean ordinaryRate = List.of("LOSS", "MANUFACTURE").contains(text(v.code()));
      if (ordinaryRate) numeric(row, 3, v.rate(), PERCENT);
      numeric(row, 4, v.quantity(), DECIMAL);
      numeric(row, 5, "PROPERTY".equals(v.kind()) ? v.coefficient() : v.amount(),
          "PROPERTY".equals(v.kind()) ? DECIMAL : MONEY);
      numeric(row, 6, v.grossWeight(), DECIMAL); numeric(row, 7, v.netWeight(), DECIMAL);
      numeric(row, 8, v.rawUnitPrice(), MONEY); numeric(row, 9, v.scrapUnitPrice(), MONEY);
      string(row, 10, v.material()); string(row, 11, v.shape());
      string(row, 12, "HOUSEHOLD".equals(v.kind()) ? "家用部件含二项费用询价" : v.priceSource());
      string(row, 13, compact(v.remark(), 12));
      if (v.rate() != null && !ordinaryRate) numeric(row, 14, v.rate(), "ADJUSTED_MANUFACTURE_COST".equals(v.code()) ? DECIMAL : PERCENT);
      else if ("HOUSEHOLD".equals(v.kind())) string(row, 14, "H=" + percent(v.householdRate()) + "；S=" + percent(v.salesRate()));
      else if ("FEE".equals(v.kind())) string(row, 14, switch (text(v.feeState())) {
        case "MISSING" -> "未填写"; case "EXPLICIT_NONE" -> "明确无费用";
        case "EXPLICIT_AMOUNT" -> "有效填写金额"; case "LEGACY" -> "历史保存值"; default -> "";
      });
      comment(row.getCell(13), text(v.remark()));
      comment(row.getCell(14), v.toString());
      pageHeight += row.getHeightInPoints();
    }

    int prototype(JjbReadModel.Row v) {
      if ("PART".equals(v.kind()) || "EXPLANATION".equals(v.kind())) return 6;
      if ("HOUSEHOLD".equals(v.kind())) return 58;
      if ("PROPERTY".equals(v.kind())) return 70;
      if ("PRODUCT_FEES".equals(v.section())) return switch (text(v.name())) {
        case "模具费" -> 65; case "认证费" -> 66; case "折旧费" -> 67; case "工装夹具费" -> 68; default -> 69;
      };
      if ("THREE_EXPENSES".equals(v.section())) return switch (text(v.code())) {
        case "MGMT_EXP" -> 54; case "SALES_EXP" -> 55; case "FIN_EXP" -> 56; default -> 57;
      };
      if ("SUBTOTAL".equals(v.kind()) || "CONTRIBUTION".equals(v.kind())) return 64;
      return switch (text(v.code())) {
        case "MATERIAL" -> 48; case "DIRECT_LABOR" -> 49; case "INDIRECT_LABOR" -> 50;
        case "LOSS" -> 51; case "MANUFACTURE" -> 52; case "MANUFACTURE_COST" -> 53;
        default -> 41;
      };
    }

    void totals(JjbReadModel.Totals totals) {
      reserve(58);
      String[] labels = {"不含税总成本1", "材料成本差异", "不含税总成本2"};
      String[] amounts = {totals.totalCost(), totals.cuMaterialAdjustment(), totals.finalQuoteAmount()};
      for (int i = 0; i < 3; i++) {
        Row row = create(71 + i);
        merge(row, 0, 2, labels[i]); numeric(row, 5, amounts[i], MONEY);
        if (amounts[i] == null) string(row, 13, "历史未记录");
        pageHeight += row.getHeightInPoints();
      }
    }

    void note(String text) {
      Row row = create(74);
      merge(row, 0, 14, text);
      row.getCell(0).setCellStyle(formatted(row.getCell(0).getCellStyle(), "@", false));
      comment(row.getCell(0), text);
    }

    Row create(int prototype) {
      Row row = sheet.createRow(next++);
      row.setHeightInPoints(heights[prototype]);
      for (int c = 0; c < 15; c++) row.createCell(c).setCellStyle(template[prototype][c]);
      return row;
    }

    void set(int row, int col, String value) { string(sheet.getRow(row), col, value); }

    void headerFont(Cell cell, boolean label) {
      String key = cell.getCellStyle().getIndex() + ":header:" + label;
      cell.setCellStyle(formats.computeIfAbsent(key, k -> {
        CellStyle style = wb.createCellStyle();
        style.cloneStyleFrom(cell.getCellStyle());
        style.setFont(wb.getFontAt(template[label ? 1 : 6][label ? 3 : 1].getFontIndexAsInt()));
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
      }));
    }

    void merge(Row row, int from, int to, String value) {
      string(row, from, value);
      sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), from, to));
    }

    void string(Row row, int column, String value) {
      Cell cell = row.getCell(column);
      cell.setCellStyle(formatted(cell.getCellStyle(), "@", true));
      if (value == null || value.isEmpty()) cell.setBlank(); else cell.setCellValue(value);
    }

    void numeric(Row row, int column, String value, String format) {
      Cell cell = row.getCell(column);
      cell.setCellStyle(formatted(cell.getCellStyle(), format, true));
      if (value == null || value.isEmpty()) { cell.setBlank(); return; }
      BigDecimal decimal = new BigDecimal(value);
      double number = decimal.doubleValue();
      if (decimal.stripTrailingZeros().precision() <= 15 && Double.isFinite(number)
          && BigDecimal.valueOf(number).compareTo(decimal) == 0) cell.setCellValue(number);
      else {
        cell.setCellStyle(formatted(cell.getCellStyle(), "@", true));
        cell.setCellValue(PERCENT.equals(format) ? percent(value) : value);
      }
    }

    CellStyle formatted(CellStyle base, String format, boolean shrink) {
      String key = base.getIndex() + ":" + format + ":" + shrink;
      return formats.computeIfAbsent(key, k -> {
        CellStyle result = wb.createCellStyle(); result.cloneStyleFrom(base);
        result.setDataFormat(wb.createDataFormat().getFormat(format));
        result.setShrinkToFit(shrink); result.setWrapText(!shrink);
        return result;
      });
    }

    void comment(Cell cell, String text) {
      if (text == null || text.isEmpty()) return;
      if (text.length() > 32000) {
        int split = Character.isHighSurrogate(text.charAt(31999)) ? 31999 : 32000;
        comment(cell, text.substring(0, split));
        int col = cell.getColumnIndex() + 1;
        if (col >= 15) throw new IllegalArgumentException("保存依据超出Excel单行批注容量");
        comment(cell.getRow().getCell(col), text.substring(split));
        return;
      }
      var anchor = wb.getCreationHelper().createClientAnchor();
      anchor.setCol1(cell.getColumnIndex()); anchor.setCol2(Math.min(15, cell.getColumnIndex() + 4));
      anchor.setRow1(cell.getRowIndex()); anchor.setRow2(cell.getRowIndex() + 8);
      Comment comment = cell.getCellComment();
      if (comment == null) comment = drawing.createCellComment(anchor);
      comment.setAuthor("核算依据"); comment.setString(wb.getCreationHelper().createRichTextString(text));
      cell.setCellComment(comment);
    }

    void reserve(float height) {
      if (pageHeight > 0 && pageHeight + height > 650) { sheet.setRowBreak(next - 1); pageHeight = 0; }
    }

    void configurePrint() {
      var setup = sheet.getPrintSetup();
      setup.setPaperSize(PrintSetup.A4_PAPERSIZE); setup.setLandscape(true);
      setup.setFitWidth((short) 1); setup.setFitHeight((short) 0);
      sheet.setFitToPage(true); sheet.setAutobreaks(true);
      sheet.setMargin(Sheet.LeftMargin, .25); sheet.setMargin(Sheet.RightMargin, .25);
      sheet.setMargin(Sheet.TopMargin, .35); sheet.setMargin(Sheet.BottomMargin, .7);
      sheet.setMargin(Sheet.FooterMargin, .2);
      sheet.setDisplayGridlines(false); sheet.setPrintGridlines(false);
      sheet.getFooter().setLeft("&\"宋体,Regular\"&10见机表3");
      sheet.getFooter().setRight("&\"宋体,Regular\"&10第 &P 页 / 共 &N 页");
      wb.setPrintArea(0, 0, 14, 0, next - 1);
    }
  }

  private static String compact(String value, int limit) {
    String text = text(value).replace('\n', ' ');
    if (text.codePointCount(0, text.length()) <= limit) return text;
    return text.substring(0, text.offsetByCodePoints(0, limit)) + "…";
  }
  private static String percent(String value) {
    return value == null ? "" : new BigDecimal(value).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
  }
  private static String text(Object value) { return value == null ? "" : value.toString(); }
}
