package com.sanhua.marketingcost.service.financefactor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook;
import com.sanhua.marketingcost.dto.FinanceFactorWorkbook.*;
import com.sanhua.marketingcost.service.FactorCanonicalKeyService;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 可编辑月度表：方案身份与价格分开，导出当前有效值，不重放原稿公式或单位换算。 */
final class FinanceFactorEditWorkbook {
  private static final String FORMAT = "FINANCE_MONTHLY_EDIT_V1";
  private static final String FORMAT_PROPERTY = "finance.base.edit.format";
  private static final String ORGANIZATION_PROPERTY = "finance.base.edit.organization";
  private static final String COLUMNS_PROPERTY = "finance.base.edit.columns";
  private static final String[] HEADERS = {"基价名称", "基价代号", "取价来源", "计量单位", "税口径"};
  private static final ObjectMapper JSON = new ObjectMapper();
  record Column(String scheme, Condition condition) {}

  static byte[] write(String businessUnit, String organization, String month, int revision,
      String defaultScheme, List<Value> values) {
    if (values.isEmpty()) throw new IllegalStateException("上月发布没有可导出的财务基价");
    var columns = values.stream().map(v -> new Column(v.schemeCode(), v.condition())).distinct()
        .sorted(Comparator.comparing((Column c) -> !c.scheme().equals(defaultScheme))).toList();
    validateColumns(columns, organization);
    if (columns.stream().noneMatch(c -> c.scheme().equals(defaultScheme)))
      throw new IllegalStateException("上月财务发布的默认方案缺失，不能导出");
    var rows = new LinkedHashMap<String, Map<Column, Value>>();
    for (Value value : values) {
      new FactorPriceScope(businessUnit, organization, month, value.schemeCode(), value.condition());
      var cells = rows.computeIfAbsent(value.canonicalKey(), ignored -> new LinkedHashMap<>());
      if (cells.putIfAbsent(new Column(value.schemeCode(), value.condition()), value) != null)
        throw new IllegalStateException("财务发布存在重复因素/方案/条件，不能导出");
    }
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      var properties = book.getProperties().getCustomProperties();
      properties.addProperty(FORMAT_PROPERTY, FORMAT);
      properties.addProperty(ORGANIZATION_PROPERTY, organization);
      properties.addProperty(COLUMNS_PROPERTY, JSON.writeValueAsString(columns));
      var sheet = book.createSheet("210".equals(organization) ? "商用财务基价" : "板换财务基价");
      int lastColumn = HEADERS.length + columns.size() - 1;
      sheet.createRow(0).createCell(0).setCellValue(sheet.getSheetName() + "月度维护表");
      sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastColumn));
      sheet.createRow(1).createCell(0).setCellValue("来源：" + month + " 第" + revision
          + "次发布。修改价格后，在页面选择新月份导入；请保留因素、单位及方案列。");
      sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastColumn));
      var headerStyle = book.createCellStyle();
      headerStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
      headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      headerStyle.setWrapText(true); headerStyle.setAlignment(HorizontalAlignment.CENTER);
      headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
      var bold = book.createFont(); bold.setBold(true); headerStyle.setFont(bold);
      var priceStyle = book.createCellStyle();
      priceStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
      priceStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      priceStyle.setDataFormat(book.createDataFormat().getFormat("@"));
      priceStyle.setAlignment(HorizontalAlignment.RIGHT); priceStyle.setVerticalAlignment(VerticalAlignment.CENTER);
      var labelStyle = book.createCellStyle(); labelStyle.setWrapText(true);
      labelStyle.setVerticalAlignment(VerticalAlignment.CENTER);
      var header = sheet.createRow(2);
      header.setHeightInPoints(30);
      for (int c = 0; c < HEADERS.length; c++) header.createCell(c).setCellValue(HEADERS[c]);
      for (int c = 0; c < columns.size(); c++) {
        Column column = columns.get(c);
        header.createCell(HEADERS.length + c).setCellValue(columnLabel(column));
      }
      header.forEach(c -> c.setCellStyle(headerStyle));
      for (var prices : rows.values()) {
        Value first = prices.values().iterator().next();
        if (prices.size() != columns.size() || prices.values().stream().anyMatch(v ->
            !v.unit().equals(first.unit()) || v.taxIncluded() != first.taxIncluded()))
          throw new IllegalStateException("财务发布的方案因素或单位不完整一致，不能生成月度编辑表：" + first.shortName());
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        String unit = switch (first.unit()) {
          case "CNY/KG" -> "元/公斤"; case "CNY/TON" -> "元/吨"; case "CNY/G" -> "元/克";
          default -> throw new IllegalStateException("财务因素单位无效：" + first.unit());
        };
        String[] labels = {first.factorName(), first.shortName(), first.priceSource(), unit,
            first.taxIncluded() ? "含税" : "不含税"};
        for (int c = 0; c < labels.length; c++) {
          var cell = row.createCell(c); cell.setCellValue(labels[c]); cell.setCellStyle(labelStyle);
        }
        row.setHeightInPoints(Math.max(30, Math.min(112, (float) Math.ceil(first.factorName().length() / 29.0) * 16)));
        for (int c = 0; c < columns.size(); c++) {
          var cell = row.createCell(HEADERS.length + c);
          // Excel 数字只有15位精度。文本保留完整十进制值，导入也接受财务手填的数字。
          cell.setCellValue(prices.get(columns.get(c)).price().stripTrailingZeros().toPlainString());
          cell.setCellStyle(priceStyle);
        }
      }
      sheet.setColumnWidth(0, 62 * 256); sheet.setColumnWidth(1, 23 * 256);
      for (int c = 2; c <= lastColumn; c++) sheet.setColumnWidth(c, (c < 5 ? 15 : 25) * 256);
      sheet.getRow(0).setHeightInPoints(26); sheet.getRow(1).setHeightInPoints(30);
      sheet.createFreezePane(5, 3);
      sheet.setAutoFilter(new CellRangeAddress(2, sheet.getLastRowNum(), 0, lastColumn));
      sheet.getPrintSetup().setLandscape(true); sheet.setFitToPage(true);
      sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
      book.write(output); return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("财务基价导出失败", error);
    }
  }

  /** 无月度维护标识时继续使用原商用/板换原稿解析器。 */
  static FinanceFactorWorkbook parseIfSupported(Workbook workbook, byte[] bytes, String fileName,
      String organization, String requestedSheet, FactorCanonicalKeyService canonicalKeys) throws IOException {
    if (!(workbook instanceof XSSFWorkbook book)) return null;
    var properties = book.getProperties().getCustomProperties();
    if (!properties.contains(FORMAT_PROPERTY)) return null;
    if (!FORMAT.equals(properties.getProperty(FORMAT_PROPERTY).getLpwstr()))
      throw new IllegalArgumentException("不支持的财务月度维护表格式，请重新导出");
    if (!properties.contains(ORGANIZATION_PROPERTY)
        || !organization.equals(properties.getProperty(ORGANIZATION_PROPERTY).getLpwstr()))
      throw new IllegalArgumentException("商用与板换基价文件不能混用，请选择对应组织");
    if (book.getNumberOfSheets() != 1) throw new IllegalArgumentException("财务月度维护表必须保留一个工作表");
    var sheet = book.getSheetAt(0);
    if (requestedSheet != null && !requestedSheet.isBlank() && !sheet.getSheetName().equals(requestedSheet))
      throw new IllegalArgumentException("所选Sheet与财务月度维护表不一致");
    if (!properties.contains(COLUMNS_PROPERTY)) throw new IllegalArgumentException("月度维护表方案信息缺失，请重新导出");
    List<Column> columns = JSON.readValue(properties.getProperty(COLUMNS_PROPERTY).getLpwstr(), new TypeReference<>() {});
    validateColumns(columns, organization);
    var header = sheet.getRow(2);
    for (int c = 0; c < HEADERS.length; c++)
      if (!HEADERS[c].equals(text(header == null ? null : header.getCell(c))))
        throw new IllegalArgumentException("月度维护表表头已改变，请保留导出表格式");
    for (Cell cell : header)
      if (cell.getColumnIndex() >= HEADERS.length + columns.size() && !blank(cell))
        throw new IllegalArgumentException("月度维护表方案列已改变，请保留全部方案");
    for (int c = 0; c < columns.size(); c++)
      if (!columnLabel(columns.get(c)).equals(text(header.getCell(HEADERS.length + c))))
        throw new IllegalArgumentException("月度维护表方案列已改变，请保留全部方案");
    var values = new ArrayList<Value>(); var problems = new ArrayList<Problem>();
    var evaluator = book.getCreationHelper().createFormulaEvaluator();
    for (int r = 3; r <= sheet.getLastRowNum(); r++) {
      Row row = sheet.getRow(r);
      if (row == null || java.util.stream.StreamSupport.stream(row.spliterator(), false)
          .allMatch(FinanceFactorEditWorkbook::blank)) continue;
      try {
        String name = text(row.getCell(0)), shortName = text(row.getCell(1)), source = text(row.getCell(2));
        if (name.isBlank() || shortName.isBlank() || source.isBlank()) throw new IllegalArgumentException("因素名称、代号及取价来源必填");
        for (Cell cell : row)
          if (cell.getColumnIndex() >= HEADERS.length + columns.size() && !blank(cell))
            throw new IllegalArgumentException("存在未识别的额外价格列，请保留导出表格式");
        String unit = FinanceFactorWorkbookParser.unit(text(row.getCell(3)));
        String tax = text(row.getCell(4));
        if (!Set.of("含税", "不含税").contains(tax)) throw new IllegalArgumentException("税口径须为含税或不含税");
        String key = canonicalKeys.build(source, shortName);
        for (int c = 0; c < columns.size(); c++) {
          int index = HEADERS.length + c; Cell cell = row.getCell(index);
          try {
            if (blank(cell)) throw new IllegalArgumentException("基价不能为空；零价请明确填写0");
            var price = FinanceFactorWorkbookParser.number(cell, evaluator);
            FinanceFactorWorkbookParser.validatePrice(price);
            if (organization.equals("210") && key.equals("AVG|1#CU") && price.signum() == 0)
              throw new IllegalArgumentException("商用方案Cu基价必须大于0");
            Column column = columns.get(c);
            values.add(new Value(key, name, shortName, source, column.scheme(), column.condition(), price,
                unit, tax.equals("含税"), r + 1, index + 1, new CellReference(r, index).formatAsString(),
                FinanceFactorWorkbookParser.rawValue(cell, price), price,
                cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null,
                cell.getCellStyle().getDataFormatString(), text(row.getCell(3)), "MONTHLY_EDIT_VALUE"));
          } catch (RuntimeException invalid) {
            problems.add(new Problem(sheet.getSheetName(), new CellReference(r, index).formatAsString(), invalid.getMessage()));
          }
        }
      } catch (RuntimeException invalid) {
        problems.add(new Problem(sheet.getSheetName(), "A" + (r + 1), invalid.getMessage()));
      }
    }
    if (values.isEmpty()) problems.add(new Problem(sheet.getSheetName(), null, "未找到有效基价行"));
    if (organization.equals("210"))
      for (Column column : columns)
        if (values.stream().noneMatch(v -> v.canonicalKey().equals("AVG|1#CU") && v.schemeCode().equals(column.scheme())))
          problems.add(new Problem(sheet.getSheetName(), null, "商用方案缺少有效Cu因素：" + column.scheme()));
    return new FinanceFactorWorkbook(fileName, FinanceFactorWorkbookParser.sha256(bytes), sheet.getSheetName(),
        FORMAT, columns.stream().map(c -> new Scheme(c.scheme(), c.scheme())).distinct().toList(), values, problems);
  }

  private static void validateColumns(List<Column> columns, String organization) {
    if (columns == null || columns.isEmpty() || columns.size() > 128 || new HashSet<>(columns).size() != columns.size())
      throw new IllegalArgumentException("月度维护表方案信息无效");
    for (Column c : columns) {
      if (c == null || c.scheme() == null || c.scheme().length() > 64
          || !c.scheme().matches("[A-Z0-9][A-Z0-9_-]*") || c.scheme().equals("LEGACY") || c.condition() == null)
        throw new IllegalArgumentException("月度维护表方案信息无效");
      if ("210".equals(organization) && c.condition() != Condition.GENERAL)
        throw new IllegalArgumentException("商用月度维护表不能使用板换招标条件");
      if ("220".equals(organization) && (c.condition() == Condition.GENERAL || !"BOARD".equals(c.scheme())))
        throw new IllegalArgumentException("板换月度维护表须使用招标/非招标条件");
    }
    if ("220".equals(organization) && !new HashSet<>(columns).equals(Set.of(
        new Column("BOARD", Condition.NON_TENDER), new Column("BOARD", Condition.TENDER))))
      throw new IllegalArgumentException("板换月度维护表须同时保留招标和非招标两列");
  }

  private static String text(Cell cell) {
    return cell != null && cell.getCellType() == CellType.STRING ? cell.getStringCellValue().trim() : "";
  }

  private static boolean blank(Cell cell) {
    return cell == null || cell.getCellType() == CellType.BLANK
        || (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank());
  }

  private static String columnLabel(Column column) {
    if (column.condition() != Condition.GENERAL) return column.condition() == Condition.TENDER ? "招标基价" : "非招标基价";
    return column.scheme().startsWith("CU_") ? "Cu " + column.scheme().substring(3).replace('_', '.') + " 元/吨方案" : column.scheme();
  }
}
