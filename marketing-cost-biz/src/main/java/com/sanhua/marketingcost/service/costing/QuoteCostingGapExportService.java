package com.sanhua.marketingcost.service.costing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从已检查的缺口导出待补清单；料号去重、供应商整组保留，不重新核算或采用月价。 */
@Service
public class QuoteCostingGapExportService {
  public enum Category {
    RATIO("缺供货比率"), PRICE("缺价格"), OTHER("其他原因");
    public final String label;
    Category(String label) { this.label = label; }
  }
  private static final Pattern SUMMARY = Pattern.compile(
      "(缺少供货比率|供货比率不足|缺少原料价格|缺少废料价格|缺少可用价格|原料价格待核对|废料价格待核对|价格待核对|计价资料待完善|取价异常|价格待就绪)：([^；]+)");
  private static final Pattern ERROR_MATERIAL = Pattern.compile("(?:物料|料号|父件)[=:：]([^，；,\\s]+)");
  public static final List<String> RATIO_HEADERS = List.of("组织", "来源", "供应商名称", "供应商代码", "采购分类",
      "物料名称", "物料代码", "规格型号", "所属事业部", "供货比例");
  private final QuoteRequestQueryService quotes;
  private final MaterialMasterRawMapper materials;
  private final SupplierSupplyRatioMapper ratios;
  private final NamedParameterJdbcTemplate sql;
  private final ObjectMapper json;
  private final QuotePricePrepareWorkbenchService prices;
  private final QuotePricingPeriod periods;
  private final CostingPriceSourceReview sourceReview;

  public QuoteCostingGapExportService(QuoteRequestQueryService quotes, MaterialMasterRawMapper materials,
      SupplierSupplyRatioMapper ratios, JdbcTemplate jdbc, ObjectMapper json,
      QuotePricePrepareWorkbenchService prices, QuotePricingPeriod periods,
      CostingPriceSourceReview sourceReview) {
    this.quotes = quotes; this.materials = materials; this.ratios = ratios;
    this.sql = new NamedParameterJdbcTemplate(jdbc); this.json = json; this.prices = prices; this.periods = periods;
    this.sourceReview = sourceReview;
  }

  static final class Gap {
    final String code;
    final Set<String> reasons = new LinkedHashSet<>(), products = new LinkedHashSet<>();
    final QuoteRequestItemResponse product;
    boolean priceProblem;
    List<CostingPriceSourceReview.Check> sourceChecks = List.of();
    Gap(String code, QuoteRequestItemResponse product) { this.code = code; this.product = product; }
  }

  @Transactional(readOnly = true)
  public byte[] export(String oaNo, Category category) throws IOException {
    var data = collect(oaNo, category);
    try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
      if (category == Category.RATIO) ratioSheet(book, data.gaps(), data.masters(), data.unit(), data.month());
      detailSheet(book, category.label + "明细", data.gaps(), data.masters());
      book.write(output); return output.toByteArray();
    }
  }

  public record Issue(String materialCode, String materialName, String model, String specification,
      String unit, String reason, List<String> affectedProducts, Long firstItemId,
      List<CostingPriceSourceReview.Check> sourceChecks) {}
  public record Details(String category, int materialCount, List<Issue> records) {}
  private record Data(Map<String, Gap> gaps, Map<String, MaterialMasterRaw> masters, String unit, String month) {}

  @Transactional(readOnly = true)
  public Details details(String oaNo, Category category) throws IOException {
    var data = collect(oaNo, category);
    var rows = data.gaps().values().stream().map(g -> {
      var master = data.masters().get(key(g.code));
      boolean product = key(g.code).equals(key(productCode(g.product)));
      return new Issue(g.code, first(master == null ? null : master.getMaterialName(), product ? g.product.getProductName() : null),
          first(master == null ? null : master.getMaterialModel(), product ? g.product.getSunlModel() : null),
          first(master == null ? null : master.getMaterialSpec(), product ? g.product.getSpec() : null),
          master == null ? "" : text(master.getUnit()), String.join("；", g.reasons), List.copyOf(g.products),
          g.product.getId(), g.sourceChecks);
    }).toList();
    return new Details(category.name(), rows.size(), rows);
  }

  private Data collect(String oaNo, Category category) throws IOException {
    // 与原详情入口共用事业部、报价可见范围及产品归属验证。
    var quote = quotes.getRequestDetail(oaNo);
    if (!com.sanhua.marketingcost.security.QuoteVisibilityContext.canAccessForm(quote.getId())
        || !com.sanhua.marketingcost.security.BusinessUnitContext.isAdmin()
            && !Objects.equals(quote.getBusinessUnitType(), com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType()))
      throw new IllegalArgumentException("报价单不存在或不属于当前可见范围");
    String unit = quote.getBusinessUnitType(), month = periods.currentMonth(unit);
    Map<String, Gap> gaps = new LinkedHashMap<>();
    var tasks = sql.queryForList("""
        SELECT t.oa_form_item_id,t.result_summary_json FROM lp_cost_run_task t
        JOIN (SELECT oa_form_item_id,MAX(id) id FROM lp_cost_run_task
          WHERE oa_no=:oa AND pricing_month=:month GROUP BY oa_form_item_id) latest ON t.id=latest.id
        """, Map.of("oa", oaNo, "month", month));
    Map<Long, String> saved = new HashMap<>();
    for (var task : tasks) saved.put(((Number) task.get("oa_form_item_id")).longValue(), text(task.get("result_summary_json")));
    for (var item : quote.getItems()) {
      var workspace = item.getCostingWorkspace();
      if (workspace == null || Set.of("SUCCESS", "RUNNING").contains(text(workspace.getWorkspaceStatus()))) continue;
      String message = text(workspace.getLastErrorMessage());
      if (message.isBlank()) continue;
      List<PricePrepareGap> structured = savedGaps(saved.get(item.getId()), message);
      if (!structured.isEmpty()) collectStructured(gaps, structured, item, category);
      else if (message.matches("(?s).*等\\d+种物料.*")) {
        // 旧检查只保存了摘要。仅对被截断或需展开具体原因的旧产品补做只读检查。
        var request = new QuotePricePrepareGenerateRequest(); request.setPeriodMonth(month); request.setRefreshCandidates(true);
        try {
          var response = prices.checkPriceSources(oaNo, item.getId(), request);
          PricePrepareGapPresentation.enrich(response);
          var rows = response.getGaps() == null ? List.<PricePrepareGap>of() : response.getGaps().getRecords();
          if (rows == null || rows.isEmpty()) collectSummary(gaps, item, category, message);
          else collectStructured(gaps, rows, item, category);
        } catch (RuntimeException failure) {
          if (message.matches("(?s).*等\\d+种物料.*"))
            throw new IllegalArgumentException("产品" + productCode(item) + "的完整缺口读取失败：" + failure.getMessage(), failure);
          // 其他原因表本来就是供人处理异常，单品检查失败仍保留其料号及具体原因。
          collectSummary(gaps, item, category, message);
          for (var gap : gaps.values()) if (gap.products.contains(productCode(item)))
            gap.reasons.add("补充检查未完成：" + failure.getMessage());
        }
      } else collectSummary(gaps, item, category, message);
    }
    if (category == Category.OTHER) enrichManufacturingGaps(oaNo, unit, month, gaps);
    if (category != Category.RATIO) {
      var review = sourceReview.read(unit, month, gaps.values().stream().filter(g -> g.priceProblem).map(g -> g.code).toList());
      gaps.values().removeIf(g -> {
        if (!g.priceProblem) return category == Category.PRICE;
        var found = review.get(key(g.code));
        if (found == null) throw new IllegalStateException("未完成价格来源核对：" + g.code);
        if (found.absent() != (category == Category.PRICE)) return true;
        var old = List.copyOf(g.reasons);
        g.reasons.clear(); g.reasons.add(found.reason()); g.sourceChecks = found.sources();
        if (!found.absent()) for (var reason : old) {
          int at = reason.indexOf("缺少行情档位");
          if (at >= 0) g.reasons.add("取价检查：" + reason.substring(at).split("；", 2)[0]);
          else if (Set.of("财务指定", "年用量", "币种", "税口径", "税价口径", "主供", "指定供应商",
              "未发布", "未生效", "规格不匹配", "数量未命中", "适用档位").stream().anyMatch(reason::contains)) g.reasons.add(reason);
        }
        return false;
      });
    }
    Map<String, MaterialMasterRaw> masters = new HashMap<>();
    if (!gaps.isEmpty()) for (var row : materials.selectByLatestBatchAndCodes(gaps.keySet(), null, unit))
      masters.put(key(row.getMaterialCode()), row);
    return new Data(gaps, masters, unit, month);
  }

  private void enrichManufacturingGaps(String oaNo, String unit, String month, Map<String, Gap> gaps) {
    var codes = gaps.values().stream().filter(g -> !g.priceProblem && g.reasons.contains("计价资料待完善")).map(g -> g.code).toList();
    if (codes.isEmpty()) return;
    var seen = new HashSet<String>();
    var rows = sql.query("SELECT parent_material_no,child_material_no,status,gross_weight_g,net_weight_g,remark"
        + " FROM lp_make_part_price_calc_row WHERE oa_no=:oa AND business_unit_type=:unit AND pricing_month=:month"
        + " AND (parent_material_no IN (:codes) OR child_material_no IN (:codes))"
        + " AND status IN ('MISSING_WEIGHT','MISSING_SCRAP_MAPPING','INVALID_PROCESS_TYPE') ORDER BY id DESC",
        Map.of("oa", oaNo, "unit", unit, "month", month, "codes", codes),
        org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(com.sanhua.marketingcost.entity.MakePartPriceCalcRow.class));
    for (var row : rows) for (var code : List.of(text(row.getParentMaterialNo()), text(row.getChildMaterialNo()))) {
      var gap = gaps.get(key(code));
      if (gap != null && gap.reasons.contains("计价资料待完善") && seen.add(key(code))) {
        gap.reasons.remove("计价资料待完善"); gap.reasons.add(FinanceCostingMessages.manufacturingGap(row));
      }
    }
    for (var code : codes) {
      var gap = gaps.get(key(code));
      if (gap.reasons.remove("计价资料待完善")) gap.reasons.add("旧检查未记录具体缺项，请重新核算关联产品后确认重量或结构资料");
    }
  }

  private void collectStructured(Map<String, Gap> gaps, List<PricePrepareGap> rows,
      QuoteRequestItemResponse item, Category category) {
    if (category == Category.RATIO) addStructured(gaps, rows, item, category);
    else { addStructured(gaps, rows, item, Category.PRICE); addStructured(gaps, rows, item, Category.OTHER); }
  }
  private static void collectSummary(Map<String, Gap> gaps, QuoteRequestItemResponse item, Category category, String message) {
    if (category == Category.RATIO) addSummary(gaps, item, category, message);
    else { addSummary(gaps, item, Category.PRICE, message); addSummary(gaps, item, Category.OTHER, message); }
  }

  private List<PricePrepareGap> savedGaps(String value, String message) throws IOException {
    if (value == null || value.isBlank()) return List.of();
    var root = json.readTree(value);
    if (!message.equals(root.path("message").asText())) return List.of();
    var result = new ArrayList<PricePrepareGap>();
    for (var node : root.path("priceGaps")) result.add(json.treeToValue(node, PricePrepareGap.class));
    return result;
  }

  private static Category category(String label) {
    return label.contains("供货比率") ? Category.RATIO
        : Set.of("缺少原料价格", "缺少废料价格", "缺少可用价格", "原料价格待核对", "废料价格待核对", "价格待核对").contains(label) ? Category.PRICE : Category.OTHER;
  }

  static void addSummary(Map<String, Gap> result, QuoteRequestItemResponse item, Category requested, String message) {
    var matcher = SUMMARY.matcher(message); boolean recognized = false;
    while (matcher.find()) {
      recognized = true;
      if (category(matcher.group(1)) != requested) continue;
      for (var code : matcher.group(2).split("、")) add(result, code, item, matcher.group(1));
    }
    if (!recognized && requested == Category.OTHER) {
      var codes = ERROR_MATERIAL.matcher(message); boolean found = false;
      while (codes.find()) { add(result, codes.group(1), item, message); found = true; }
      if (!found) add(result, productCode(item), item, message);
    }
  }

  private void addStructured(Map<String, Gap> result, List<PricePrepareGap> rows,
      QuoteRequestItemResponse item, Category requested) {
    var response = new QuotePricePrepareWorkbenchResponse();
    response.setGaps(new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse(rows.size(), rows));
    if (rows.stream().anyMatch(row -> text(row.getDisplayGapType()).isBlank())) PricePrepareGapPresentation.enrich(response);
    for (var row : rows) {
      if (category(text(row.getDisplayGapType())) != requested) continue;
      String code = first(row.getGapMaterialCode(), row.getMaterialCode(), productCode(item));
      add(result, code, item, first(row.getDisplayMessage(), row.getMessage(), row.getDisplayGapType()));
      if (requested == Category.PRICE || "PRICE_SOURCE_ERROR".equals(row.getGapType())) result.get(key(code)).priceProblem = true;
    }
  }

  private static void add(Map<String, Gap> result, String code, QuoteRequestItemResponse item, String reason) {
    if (text(code).isBlank()) return;
    var gap = result.computeIfAbsent(key(code), ignored -> new Gap(code.trim(), item));
    gap.reasons.add(reason); gap.products.add(productCode(item));
    gap.priceProblem |= Set.of("缺少原料价格", "缺少废料价格", "缺少可用价格", "原料价格待核对", "废料价格待核对", "价格待核对", "取价异常", "价格待就绪").contains(reason)
        || reason.contains("缺少行情档位");
  }

  private void ratioSheet(XSSFWorkbook book, Map<String, Gap> gaps, Map<String, MaterialMasterRaw> masters,
      String unit, String month) {
    var sheet = sheet(book, "供货比率补齐", RATIO_HEADERS);
    if (gaps.isEmpty()) return;
    var params = new HashMap<String, Object>(); params.put("unit", unit); params.put("codes", gaps.values().stream().map(g -> g.code).toList());
    params.put("date", LocalDate.now(CostPricingPeriodUtils.BUSINESS_ZONE)); params.put("month", month);
    Map<String, LinkedHashMap<String, Map<String, Object>>> suppliers = new LinkedHashMap<>();
    gaps.keySet().forEach(code -> suppliers.put(code, new LinkedHashMap<>()));
    for (String table : List.of("lp_price_fixed_item", "lp_price_linked_item", "lp_price_range_item")) {
      String extra = table.equals("lp_price_linked_item")
          ? " AND deleted=0 AND (rule_status IS NULL OR rule_status IN ('LEGACY','PUBLISHED')) AND (pricing_month IS NULL OR pricing_month<=:month)"
          : table.equals("lp_price_range_item") ? " AND (current_flag IS NULL OR current_flag=1)" : "";
      for (var row : sql.queryForList("SELECT org_code,source_name,supplier_name,supplier_code,purchase_class,material_name,material_code,spec_model FROM "
          + table + " WHERE business_unit_type=:unit AND material_code IN (:codes)"
          + " AND (effective_from IS NULL OR effective_from<=:date) AND (effective_to IS NULL OR effective_to>=:date)" + extra, params)) {
        var group = suppliers.get(key(text(row.get("material_code"))));
        if (group != null && !supplierKey(row).isBlank()) group.putIfAbsent(supplierKey(row), new HashMap<>(row));
      }
    }
    // 回导按料号整组替换，必须同时保留已有的0%、空比例和非本次候选的关系。
    for (var relation : ratios.currentRelations(unit, null, gaps.values().stream().map(g -> g.code).toList())) {
      var row = new HashMap<String, Object>(); row.put("supplier_code", relation.getSupplierCode());
      row.put("supplier_name", relation.getSupplierName()); row.put("ratio", relation.getSupplyRatio());
      row.put("rule", relation.getSelectionRule()); row.put("material_name", relation.getMaterialName());
      row.put("spec_model", relation.getSpecModel());
      var group = suppliers.get(key(relation.getRequestedMaterialCode()));
      if (group == null) continue;
      String identity = supplierKey(row);
      if (identity.isBlank()) continue;
      var prior = group.computeIfAbsent(identity, ignored -> new HashMap<>()); prior.putAll(row);
    }
    var percent = book.createCellStyle(); percent.setDataFormat(book.createDataFormat().getFormat("0.00%"));
    percent.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex()); percent.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    var comment = sheet.createDrawingPatriarch().createCellComment(book.getCreationHelper().createClientAnchor());
    comment.setAuthor("供货比率填写说明");
    comment.setString(book.getCreationHelper().createRichTextString(
        "填写实际供货比例，如5%；也可填写“无供货比率，取最高价”。空白表示未维护，0%表示退出供货。"
        + "请保留同一料号全部供应商，填好后到供货比率页面导入。型号、规格与原因见第二张明细表。"));
    sheet.getRow(0).getCell(9).setCellComment(comment);
    for (var gap : gaps.values()) {
      var master = masters.get(key(gap.code)); var group = suppliers.get(key(gap.code));
      // 供应商按代码去重；旧的仅名称关系只在唯一同名代码存在时合并，保留其比率。
      for (String identity : new ArrayList<>(group.keySet())) {
        var row = group.get(identity);
        if (!text(row.get("supplier_code")).isBlank()) continue;
        var named = group.entrySet().stream().filter(e -> !text(e.getValue().get("supplier_code")).isBlank()
            && key(text(e.getValue().get("supplier_name"))).equals(key(text(row.get("supplier_name"))))).toList();
        if (named.size() == 1) {
          var target = named.getFirst().getValue();
          if (row.containsKey("ratio")) { target.put("ratio", row.get("ratio")); target.put("rule", row.get("rule")); }
          group.remove(identity);
        }
      }
      if (group.isEmpty()) group.put("", Map.of());
      for (var supplier : group.values()) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        String[] values = {first(text(supplier.get("org_code")), unit.equals("PLATE") ? "220" : "210"),
            text(supplier.get("source_name")), text(supplier.get("supplier_name")), text(supplier.get("supplier_code")),
            first(text(supplier.get("purchase_class")), master == null ? null : master.getPurchaseCategory()),
            first(master == null ? null : master.getMaterialName(), text(supplier.get("material_name"))), gap.code,
            first(master == null ? null : master.getMaterialSpec(), master == null ? null : master.getMaterialModel(), text(supplier.get("spec_model"))),
            master == null ? "" : text(master.getProductionDivision()), text(supplier.get("rule"))};
        cells(row, values);
        var cell = row.getCell(9); cell.setCellStyle(percent);
        if (supplier.get("ratio") instanceof BigDecimal ratio) cell.setCellValue(ratio.doubleValue());
      }
    }
    finish(sheet, RATIO_HEADERS.size());
    int[] widths = {10, 20, 40, 20, 20, 28, 22, 45, 22, 30};
    for (int i=0;i<widths.length;i++) sheet.setColumnWidth(i,widths[i]*256);
  }

  private void detailSheet(XSSFWorkbook book, String title, Map<String, Gap> gaps, Map<String, MaterialMasterRaw> masters) {
    var headers = List.of("物料代码", "物料名称", "型号", "规格", "单位", "原因", "影响产品料号");
    var sheet = sheet(book, title, headers);
    for (var gap : gaps.values()) {
      var m = masters.get(key(gap.code)); boolean product = key(gap.code).equals(key(productCode(gap.product)));
      cells(sheet.createRow(sheet.getLastRowNum()+1), new String[]{gap.code,
          first(m == null ? null : m.getMaterialName(), product ? gap.product.getProductName() : null),
          first(m == null ? null : m.getMaterialModel(), product ? gap.product.getSunlModel() : null),
          first(m == null ? null : m.getMaterialSpec(), product ? gap.product.getSpec() : null),
          m == null ? "" : text(m.getUnit()), String.join("；", gap.reasons), String.join("、", gap.products)});
    }
    finish(sheet, headers.size()); sheet.setColumnWidth(5, 70*256); sheet.setColumnWidth(6, 55*256);
  }

  private static Sheet sheet(XSSFWorkbook book, String name, List<String> headers) {
    var sheet = book.createSheet(name); var row = sheet.createRow(0); cells(row, headers.toArray(String[]::new));
    var style = book.createCellStyle(); style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
    style.setFillPattern(FillPatternType.SOLID_FOREGROUND); var font = book.createFont(); font.setBold(true); style.setFont(font);
    row.forEach(cell -> cell.setCellStyle(style)); sheet.createFreezePane(1, 1); return sheet;
  }
  private static void finish(Sheet sheet, int columns) {
    sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, columns-1));
    for (int i=0;i<columns;i++) sheet.setColumnWidth(i, 28*256);
  }
  private static void cells(Row row, String[] values) {
    row.setHeightInPoints(30);
    for (int i=0;i<values.length;i++) row.createCell(i, CellType.STRING).setCellValue(text(values[i]));
  }
  private static String supplierKey(Map<String, Object> row) {
    String code = key(text(row.get("supplier_code"))), name = key(text(row.get("supplier_name")));
    return !code.isBlank() ? "CODE:"+code : name.isBlank() ? "" : "NAME:"+name;
  }
  private static String productCode(QuoteRequestItemResponse item) { return first(item.getMaterialNo(), item.getSunlModel(), String.valueOf(item.getId())); }
  private static String key(String value) { return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(text(value)).toUpperCase(Locale.ROOT); }
  private static String text(Object value) { return value == null ? "" : value.toString(); }
  private static String first(String... values) { return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(""); }
}
