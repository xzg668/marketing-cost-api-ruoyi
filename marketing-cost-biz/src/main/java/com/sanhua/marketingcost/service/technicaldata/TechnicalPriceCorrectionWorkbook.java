package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalPriceCorrection;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** 现有 19 列价格模板的补录关联适配，不执行另一套公式计算。 */
@Component
public class TechnicalPriceCorrectionWorkbook {
  public static final String KEY = "补录项标识";
  public static final List<String> HEADERS =
      List.of(
          "组织", "来源", "供应商名称", "供应商代码", "采购分类", "物料名称", "物料代码", "规格型号", "单位", "联动公式", "下料重", "净重",
          "加工费", "代理费", "单价", "是否含税", "生效日期", "失效日期", "订单类型", KEY);
  private static final List<String> NOTES =
      List.of(
          KEY, "报价单号", "产品行ID", "核算月份", "业务单元", "技术审批版本", "审批内容指纹", "原料号", "原重量单位", "原费用单位",
          "技术说明");

  public record ImportRow(
      String itemKey,
      String sheetName,
      int rowNumber,
      PriceItemExcelImportRow values,
      List<String> issues) {}

  public record Plan(TechnicalPriceCorrectionService.Scope scope, List<ImportRow> rows) {
    public ImportRow row(String sheet, int number) {
      return rows.stream()
          .filter(r -> r.sheetName().equals(sheet) && r.rowNumber() == number)
          .findFirst()
          .orElseThrow(
              () -> new IllegalArgumentException("导入行缺少已校验的补录关联：" + sheet + " / " + number));
    }
  }

  private final FormulaNormalizer normalizer;
  private final FormulaValidator validator;

  public TechnicalPriceCorrectionWorkbook(
      FormulaNormalizer normalizer, FormulaValidator validator) {
    this.normalizer = normalizer;
    this.validator = validator;
  }

  public byte[] export(
      TechnicalPriceCorrectionService.Scope scope, TechnicalPriceCorrection pending) {
    Set<String> keys = new HashSet<>();
    pending.items().forEach(row -> keys.add(row.itemKey()));
    if (keys.isEmpty()) throw new IllegalArgumentException("本产品当前没有待修正的自行公式");
    try (var workbook = new XSSFWorkbook();
        var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("联动价-部品6");
      var notes = workbook.createSheet("补录说明");
      write(sheet.createRow(0), HEADERS);
      write(notes.createRow(0), NOTES);
      for (var item : scope.items())
        if (keys.contains(item.itemKey())) {
          var p = item.parameters();
          BigDecimal multiplier =
              p != null && "千克".equals(p.weightUnit()) ? new BigDecimal("1000") : BigDecimal.ONE;
          var values =
              Arrays.asList(
                  item.organizationCode(),
                  "技术补录",
                  "",
                  "",
                  "",
                  "",
                  item.materialNo(),
                  "",
                  item.unit(),
                  item.formula(),
                  p == null ? "" : decimal(p.blankWeight(), multiplier),
                  p == null ? "" : decimal(p.netWeight(), multiplier),
                  p == null ? "" : text(p.processFee()),
                  p == null ? "" : text(p.agentFee()),
                  "",
                  "",
                  scope.month() + "-01",
                  "",
                  "联动",
                  item.itemKey());
          write(sheet.createRow(sheet.getLastRowNum() + 1), values);
          var metadata = new ArrayList<>(identity(scope, item));
          metadata.add(p == null ? "" : text(p.weightUnit()));
          metadata.add(p == null ? "" : text(p.feeUnit()));
          metadata.add(text(item.notes()));
          write(notes.createRow(notes.getLastRowNum() + 1), metadata);
        }
      var help = workbook.createSheet("填写说明");
      write(help.createRow(0), List.of("修正联动公式，核实是否含税；下料重、净重按既有导入口径为克，费用为本采购单位的元。"));
      write(help.createRow(1), List.of("联动公式以文本填写。单价可留空；需要影响因素时沿用现有工作簿和绑定方式。补录项标识及补录说明的归属字段不能修改。"));
      write(help.createRow(2), List.of("只导入部分料件时，其余料件继续等待处理。来源批准不代表价格可用，导入后返回原产品检查并生成最终价格。"));
      for (var page : List.of(sheet, notes)) {
        page.createFreezePane(0, 1);
        for (int col = 0; col < page.getRow(0).getLastCellNum(); col++)
          page.setColumnWidth(col, 18 * 256);
      }
      workbook.write(output);
      return output.toByteArray();
    } catch (IOException error) {
      throw new IllegalStateException("导出补录公式失败", error);
    }
  }

  public boolean hasAssociation(byte[] bytes) {
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      if (workbook.getSheet("补录说明") != null) return true;
      for (var sheet : workbook)
        for (var row : sheet)
          if (row.getRowNum() < 10)
            for (var cell : row)
              if (cell.getCellType() == CellType.STRING
                  && KEY.equals(cell.getStringCellValue().trim())) return true;
      return false;
    } catch (IOException error) {
      throw new IllegalArgumentException("不能读取补录文件", error);
    }
  }

  /** 归属错误整文件拒绝；可修正的公式／参数错误保留为逐行问题，允许其余有效行导入。 */
  public Plan validate(
      byte[] bytes, TechnicalPriceCorrectionService.Scope scope, String dataSheet, boolean type2) {
    if (bytes.length > 10 * 1024 * 1024) throw new IllegalArgumentException("补录修正文件不能超过 10MB");
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      var sheet = workbook.getSheet(dataSheet);
      var notes = workbook.getSheet("补录说明");
      if (sheet == null || notes == null)
        throw new IllegalArgumentException("文件缺少价格明细或补录说明，请使用本产品下载文件");
      Map<String, PriceItem> expected = new LinkedHashMap<>();
      scope.items().forEach(item -> expected.put(item.itemKey(), item));
      Map<String, List<String>> metadata = new HashMap<>();
      if (!header(notes.getRow(0)).keySet().containsAll(NOTES.subList(0, 8)))
        throw new IllegalArgumentException("补录说明表头被修改");
      for (int number = 1; number <= notes.getLastRowNum(); number++) {
        var row = notes.getRow(number);
        if (row == null || text(row.getCell(0)).isEmpty()) continue;
        String key = text(row.getCell(0));
        var item = expected.get(key);
        if (item == null) throw new IllegalArgumentException("补录说明包含其他审批版本的价格项：" + key);
        List<String> actual = new ArrayList<>();
        for (int col = 0; col < 8; col++) actual.add(text(row.getCell(col)));
        if (!identity(scope, item).equals(actual) || metadata.put(key, actual) != null)
          throw new IllegalArgumentException("补录说明的产品、月份、审批版本或明细归属不正确：" + key);
      }
      Row heading = null;
      Map<String, Integer> columns = Map.of();
      for (int n = 0; n < Math.min(10, sheet.getLastRowNum() + 1); n++) {
        var found = header(sheet.getRow(n));
        if (found.containsKey("物料代码")) {
          heading = sheet.getRow(n);
          columns = found;
          break;
        }
      }
      if (heading == null || !columns.containsKey(KEY))
        throw new IllegalArgumentException("价格明细缺少稳定补录项标识");
      if (sheet.getLastRowNum() > 5000) throw new IllegalArgumentException("补录修正文件不能超过 5000 行");
      Set<String> seen = new HashSet<>();
      var result = new ArrayList<ImportRow>();
      for (int n = heading.getRowNum() + 1; n <= sheet.getLastRowNum(); n++) {
        var row = sheet.getRow(n);
        if (row == null) continue;
        String material = value(row, columns, "物料代码"), key = value(row, columns, KEY);
        if (material.isEmpty() && key.isEmpty()) continue;
        PriceItem source = expected.get(key);
        if (source == null
            || !metadata.containsKey(key)
            || !seen.add(key)
            || !source.materialNo().equals(material))
          throw new IllegalArgumentException(
              "工作表 " + dataSheet + " 第 " + (n + 1) + " 行补录归属错误或重复：" + key);
        if (!Objects.equals(source.organizationCode(), value(row, columns, "组织"))
            || !Objects.equals(source.unit(), value(row, columns, "单位")))
          throw new IllegalArgumentException(
              "工作表 " + dataSheet + " 第 " + (n + 1) + " 行组织或采购单位不符合原需求，不能串用其他取价条件");
        var model = new PriceItemExcelImportRow();
        var issues = new ArrayList<String>();
        model.setMaterialCode(material);
        model.setOrgCode(source.organizationCode());
        model.setUnit(source.unit());
        model.setSourceName("技术补录");
        model.setSupplierName(value(row, columns, "供应商名称"));
        model.setSupplierCode(value(row, columns, "供应商代码"));
        model.setMaterialName(value(row, columns, "物料名称"));
        model.setSpecModel(value(row, columns, "规格型号"));
        model.setPurchaseClass(value(row, columns, "采购分类"));
        model.setOrderType(value(row, columns, "订单类型"));
        model.setFormulaExpr(type2 ? "" : value(row, columns, "联动公式"));
        model.setTaxIncluded(value(row, columns, "是否含税"));
        if (!Set.of("", "联动").contains(model.getOrderType())) issues.add("自行公式必须按联动价导入");
        if (!type2) {
          if (!Set.of("0", "1", "true", "false", "是", "否").contains(model.getTaxIncluded()))
            issues.add("请明确是否含税（0 或 1）");
          try {
            if (model.getFormulaExpr().isBlank()) throw new IllegalArgumentException("联动公式不能为空");
            validator.validate(normalizer.normalize(model.getFormulaExpr()));
          } catch (RuntimeException error) {
            issues.add("公式：" + error.getMessage());
          }
        }
        try {
          model.setBlankWeight(decimal(value(row, columns, "下料重")));
          model.setNetWeight(decimal(value(row, columns, "净重")));
          model.setProcessFee(decimal(value(row, columns, "加工费")));
          model.setAgentFee(decimal(value(row, columns, "代理费")));
          model.setUnitPrice(type2 ? null : price(row, columns));
        } catch (IllegalArgumentException error) {
          issues.add(error.getMessage());
        }
        result.add(new ImportRow(key, dataSheet, n + 1, model, List.copyOf(issues)));
      }
      if (result.isEmpty()) throw new IllegalArgumentException("文件没有可关联的自行公式行");
      return new Plan(scope, List.copyOf(result));
    } catch (IOException error) {
      throw new IllegalArgumentException("读取补录文件失败", error);
    }
  }

  private static Map<String, Integer> header(Row row) {
    Map<String, Integer> values = new LinkedHashMap<>();
    if (row != null)
      for (var cell : row) {
        String name = text(cell);
        if (!name.isBlank() && values.put(name, cell.getColumnIndex()) != null)
          throw new IllegalArgumentException("表头重复：" + name);
      }
    return values;
  }

  private static List<String> identity(
      TechnicalPriceCorrectionService.Scope scope, PriceItem item) {
    return List.of(
        item.itemKey(),
        scope.oaNo(),
        scope.itemId().toString(),
        scope.month(),
        scope.businessUnit(),
        scope.version().getId().toString(),
        scope.version().getContentFingerprint(),
        item.materialNo());
  }

  private static String value(Row row, Map<String, Integer> columns, String name) {
    Integer col = columns.get(name);
    return col == null ? "" : text(row.getCell(col));
  }

  private static BigDecimal price(Row row, Map<String, Integer> columns) {
    Integer col = columns.get("单价");
    Cell cell = col == null ? null : row.getCell(col);
    // 既有标准导入从单价 Excel 公式提取因素绑定；此处只读取其缓存参考值，实际取价仍由正式计算器验证。
    if (cell != null && cell.getCellType() == CellType.FORMULA)
      return cell.getCachedFormulaResultType() == CellType.NUMERIC
          ? BigDecimal.valueOf(cell.getNumericCellValue())
          : null;
    return decimal(text(cell));
  }

  private static String text(Cell cell) {
    if (cell == null) return "";
    if (cell.getCellType() == CellType.FORMULA)
      throw new IllegalArgumentException("补录关联及标准模板业务值应为文本或数值，公式请以文本填写");
    return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
  }

  private static String text(Object value) {
    return value == null
        ? ""
        : value instanceof BigDecimal n ? n.toPlainString() : value.toString();
  }

  private static String decimal(BigDecimal value, BigDecimal multiplier) {
    return value == null ? "" : value.multiply(multiplier).toPlainString();
  }

  private static BigDecimal decimal(String value) {
    if (value.isBlank()) return null;
    try {
      var number = new BigDecimal(value);
      if (number.signum() < 0 || number.precision() > 24 || number.scale() > 12)
        throw new IllegalArgumentException();
      return number;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("参数和单价须为非负数，最多 12 位小数：" + value);
    }
  }

  private static void write(Row row, List<String> values) {
    for (int col = 0; col < values.size(); col++)
      row.createCell(col, CellType.STRING).setCellValue(values.get(col));
  }
}
