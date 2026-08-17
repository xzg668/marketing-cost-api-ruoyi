package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/** 只读现有四类正式价格表；协作草稿永远不进入搜索范围。 */
@Repository
public class JdbcFormalPriceReferenceGateway implements FormalPriceReferenceGateway {
  static final String FIXED = "lp_price_fixed_item";
  static final String LINKED = "lp_price_linked_item";
  static final String RANGE = "lp_price_range_item";
  static final String SETTLE = "lp_price_settle_item";
  private static final int LIMIT_PER_TYPE = 30;

  private final JdbcTemplate jdbc;

  public JdbcFormalPriceReferenceGateway(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<FormalPriceReference> search(
      String businessUnitType,
      String orgCode,
      String accountingMonth,
      String keyword,
      String priceType) {
    Scope scope = scope(businessUnitType, orgCode, accountingMonth);
    String normalizedType = normalizeType(priceType, true);
    String pattern = "%" + escapeLike(keyword == null ? "" : keyword.trim()) + "%";
    List<FormalPriceReference> result = new ArrayList<>();
    if (normalizedType == null || "FIXED_PURCHASE".equals(normalizedType)) {
      result.addAll(searchFixed(scope, pattern, false));
    }
    if (normalizedType == null || "LINKED".equals(normalizedType)) {
      result.addAll(searchLinked(scope, pattern));
    }
    if (normalizedType == null || "RANGE".equals(normalizedType)) {
      result.addAll(searchRange(scope, pattern));
    }
    if (normalizedType == null || "SETTLE_FIXED".equals(normalizedType)) {
      result.addAll(searchFixed(scope, pattern, true));
      result.addAll(searchSettle(scope, pattern));
    }
    return result.stream()
        .sorted(Comparator.comparing(FormalPriceReference::priceType)
            .thenComparing(FormalPriceReference::materialCode)
            .thenComparing(FormalPriceReference::sourceType)
            .thenComparing(FormalPriceReference::sourceId).reversed())
        .limit(100)
        .toList();
  }

  @Override
  public Optional<FormalPriceReference> findEffective(
      String businessUnitType,
      String orgCode,
      String accountingMonth,
      String sourceType,
      Long sourceId) {
    Scope scope = scope(businessUnitType, orgCode, accountingMonth);
    if (sourceId == null || sourceId <= 0) return Optional.empty();
    String source = sourceType == null ? "" : sourceType.trim().toLowerCase(Locale.ROOT);
    List<FormalPriceReference> rows = switch (source) {
      case FIXED -> queryFixed(scope, sourceId);
      case LINKED -> queryLinked(scope, sourceId);
      case RANGE -> queryRange(scope, sourceId);
      case SETTLE -> querySettle(scope, sourceId);
      default -> List.of();
    };
    return rows.stream().findFirst();
  }

  private List<FormalPriceReference> searchFixed(Scope s, String pattern, boolean settle) {
    String settleClause = settle
        ? " source_type IN ('SETTLE_FIXED', 'SETTLE') "
        : " COALESCE(source_type, 'PURCHASE') IN ('PURCHASE_FIXED', 'PURCHASE') ";
    return jdbc.query("""
        SELECT * FROM lp_price_fixed_item
        WHERE business_unit_type = ?
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND """ + settleClause + """
          AND (effective_from IS NULL OR effective_from <= ?)
          AND (effective_to IS NULL OR effective_to >= ?)
          AND (material_code LIKE ? ESCAPE '\\\\' OR material_name LIKE ? ESCAPE '\\\\'
               OR spec_model LIKE ? ESCAPE '\\\\')
        ORDER BY COALESCE(effective_from, '1900-01-01') DESC, id DESC LIMIT ?
        """, (rs, rowNum) -> fixed(rs, settle),
        s.businessUnit(), s.orgCode(), s.monthEnd(), s.monthStart(),
        pattern, pattern, pattern, LIMIT_PER_TYPE);
  }

  private List<FormalPriceReference> queryFixed(Scope s, Long id) {
    return jdbc.query("""
        SELECT * FROM lp_price_fixed_item
        WHERE id = ? AND business_unit_type = ?
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND (effective_from IS NULL OR effective_from <= ?)
          AND (effective_to IS NULL OR effective_to >= ?)
        """, (rs, rowNum) -> fixed(rs, isSettleSource(rs.getString("source_type"))),
        id, s.businessUnit(), s.orgCode(), s.monthEnd(), s.monthStart());
  }

  private FormalPriceReference fixed(ResultSet rs, boolean settle) throws SQLException {
    String priceType = settle ? "SETTLE_FIXED" : "FIXED_PURCHASE";
    BigDecimal price = firstDecimal(rs, settle ? "base_settle_price" : "fixed_price",
        "current_tax_included_price", "planned_price");
    List<FormalPriceReference.Field> fields = new ArrayList<>();
    field(fields, "COMMON", "MAIN", settle ? "BASE_SETTLE_PRICE" : "PRICE",
        settle ? "基础结算价" : "固定单价",
        "DECIMAL", value(price), rs.getString("unit"), true, false, 10);
    field(fields, "COMMON", "MAIN", "PURCHASE_CLASS", "采购分类", "TEXT",
        rs.getString("purchase_class"), null, false, false, 20);
    field(fields, "COMMON", "MAIN", "ORDER_TYPE", "订单类型", "TEXT",
        rs.getString("order_type"), null, false, false, 30);
    field(fields, "COMMON", "MAIN", "QUOTA", "配额", "DECIMAL",
        value(rs.getBigDecimal("quota")), null, false, false, 40);
    if (settle) {
      field(fields, "COMMON", "MAIN", "MARKUP_RATIO", "加价比例", "DECIMAL",
          value(rs.getBigDecimal("markup_ratio")), null, false, false, 50);
      field(fields, "COMMON", "MAIN", "LINKED_SETTLE_PRICE", "联动结算价", "DECIMAL",
          value(rs.getBigDecimal("linked_settle_price")), rs.getString("unit"), false, false, 60);
    }
    return common(rs, FIXED, priceType, summary(price, rs.getString("unit")), fields,
        rs.getString("pricing_month"));
  }

  private static boolean isSettleSource(String sourceType) {
    return "SETTLE_FIXED".equalsIgnoreCase(sourceType)
        || "SETTLE".equalsIgnoreCase(sourceType);
  }

  private List<FormalPriceReference> searchLinked(Scope s, String pattern) {
    return jdbc.query("""
        SELECT * FROM lp_price_linked_item
        WHERE business_unit_type = ? AND deleted = 0
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND pricing_month <= ?
          AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
          AND (material_code LIKE ? ESCAPE '\\\\' OR material_name LIKE ? ESCAPE '\\\\'
               OR spec_model LIKE ? ESCAPE '\\\\')
        ORDER BY pricing_month DESC, effective_from DESC, id DESC LIMIT ?
        """, (rs, rowNum) -> linked(rs), s.businessUnit(), s.orgCode(), s.monthText(),
        s.monthEnd(), s.monthStart(), pattern, pattern, pattern, LIMIT_PER_TYPE);
  }

  private List<FormalPriceReference> queryLinked(Scope s, Long id) {
    return jdbc.query("""
        SELECT * FROM lp_price_linked_item
        WHERE id = ? AND business_unit_type = ? AND deleted = 0
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND pricing_month <= ?
          AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)
        """, (rs, rowNum) -> linked(rs), id, s.businessUnit(), s.orgCode(),
        s.monthText(), s.monthEnd(), s.monthStart());
  }

  private FormalPriceReference linked(ResultSet rs) throws SQLException {
    List<FormalPriceReference.Field> fields = new ArrayList<>();
    String formula = rs.getString("formula_expr");
    LinkedHashSet<String> referenced = ExpressionEvaluator.extractVariables(formula);
    field(fields, "FORMULA", "MAIN", "FORMULA_EXPR", "联动公式", "TEXT",
        formula, null, true, false, 10);
    field(fields, "FORMULA", "MAIN", "FORMULA_EXPR_CN", "公式说明", "TEXT",
        rs.getString("formula_expr_cn"), null, false, false, 20);
    if (referenced.contains("blank_weight")) {
      field(fields, "VARIABLE", "MAIN", "blank_weight", "下料重量", "DECIMAL",
          value(rs.getBigDecimal("blank_weight")), "g", true, true, 30);
    }
    if (referenced.contains("net_weight")) {
      field(fields, "VARIABLE", "MAIN", "net_weight", "净重", "DECIMAL",
          value(rs.getBigDecimal("net_weight")), "g", true, true, 40);
    }
    if (referenced.contains("process_fee") || referenced.contains("process_fee_incl")) {
      field(fields, "VARIABLE", "MAIN", "process_fee", "加工费", "DECIMAL",
          value(rs.getBigDecimal("process_fee")), "元/计价单位", true, true, 50);
    }
    if (referenced.contains("agent_fee")) {
      field(fields, "VARIABLE", "MAIN", "agent_fee", "代理费", "DECIMAL",
          value(rs.getBigDecimal("agent_fee")), "元/计价单位", true, true, 60);
    }
    appendBindingSnapshot(fields, rs.getLong("id"));
    return common(rs, LINKED, "LINKED", "公式：" + text(rs.getString("formula_expr")),
        fields, rs.getString("pricing_month"));
  }

  private void appendBindingSnapshot(List<FormalPriceReference.Field> fields, Long linkedItemId) {
    jdbc.query("""
        SELECT id, token_name, factor_code, price_source, bu_scoped
        FROM lp_price_variable_binding
        WHERE linked_item_id = ? AND expiry_date IS NULL AND deleted = 0
        ORDER BY id
        """, rs -> {
      String row = "BIND-" + rs.getLong("id");
      int sort = 1000 + fields.size() * 10;
      field(fields, "BINDING", row, "TOKEN_NAME", "系统行情占位符", "TEXT",
          rs.getString("token_name"), null, false, false, sort);
      field(fields, "BINDING", row, "FACTOR_CODE", "系统行情因子", "TEXT",
          rs.getString("factor_code"), null, false, false, sort + 1);
      field(fields, "BINDING", row, "PRICE_SOURCE", "行情价源", "TEXT",
          rs.getString("price_source"), null, false, false, sort + 2);
      field(fields, "BINDING", row, "BU_SCOPED", "按业务单元取价", "INTEGER",
          nullableIntegerText(rs, "bu_scoped"), null, false, false, sort + 3);
    }, linkedItemId);
  }

  private static String nullableIntegerText(ResultSet rs, String name) throws SQLException {
    Integer value = nullableInteger(rs, name);
    return value == null ? null : value.toString();
  }

  private List<FormalPriceReference> searchRange(Scope s, String pattern) {
    List<Long> representativeIds = jdbc.queryForList("""
        SELECT MIN(id) FROM lp_price_range_item
        WHERE business_unit_type = ? AND current_flag = 1
          AND (org_code = ? OR org_code IS NULL OR org_code = '')
          AND (effective_from IS NULL OR effective_from <= ?)
          AND (effective_to IS NULL OR effective_to >= ?)
          AND (material_code LIKE ? ESCAPE '\\\\' OR material_name LIKE ? ESCAPE '\\\\'
               OR spec_model LIKE ? ESCAPE '\\\\')
        GROUP BY business_unit_type, material_code, material_name, spec_model, org_code,
                 supplier_code, supplier_name, unit, tax_included, effective_from, effective_to,
                 range_basis, factor_rule_id, factor_code, source_name, purchase_class,
                 order_type, quota, formula_expr
        ORDER BY material_code, MIN(id) DESC LIMIT ?
        """, Long.class, s.businessUnit(), s.orgCode(), s.monthEnd(), s.monthStart(),
        pattern, pattern, pattern, LIMIT_PER_TYPE);
    List<FormalPriceReference> result = new ArrayList<>();
    for (Long id : representativeIds) {
      result.addAll(queryRange(s, id));
    }
    return result;
  }

  private List<FormalPriceReference> queryRange(Scope s, Long id) {
    List<RangeRow> rows = jdbc.query("""
        SELECT r.* FROM lp_price_range_item r
        JOIN lp_price_range_item a ON a.id = ?
         AND r.business_unit_type = a.business_unit_type
         AND r.material_code = a.material_code
         AND r.material_name <=> a.material_name AND r.spec_model <=> a.spec_model
         AND r.org_code <=> a.org_code
         AND r.supplier_code <=> a.supplier_code AND r.supplier_name <=> a.supplier_name
         AND r.unit <=> a.unit AND r.tax_included <=> a.tax_included
         AND r.effective_from <=> a.effective_from AND r.effective_to <=> a.effective_to
         AND r.range_basis <=> a.range_basis AND r.factor_rule_id <=> a.factor_rule_id
         AND r.factor_code <=> a.factor_code AND r.source_name <=> a.source_name
         AND r.purchase_class <=> a.purchase_class AND r.order_type <=> a.order_type
         AND r.quota <=> a.quota AND r.formula_expr <=> a.formula_expr
        WHERE a.business_unit_type = ? AND a.current_flag = 1 AND r.current_flag = 1
          AND (a.org_code = ? OR a.org_code IS NULL OR a.org_code = '')
          AND (a.effective_from IS NULL OR a.effective_from <= ?)
          AND (a.effective_to IS NULL OR a.effective_to >= ?)
        ORDER BY r.range_low, r.range_high, r.id
        """, (rs, rowNum) -> new RangeRow(
            rs.getLong("id"), value(rs.getBigDecimal("range_low")),
            value(rs.getBigDecimal("range_high")), value(rs.getBigDecimal("price_excl_tax")),
            value(rs.getBigDecimal("price_incl_tax")), rs.getString("unit")),
        id, s.businessUnit(), s.orgCode(), s.monthEnd(), s.monthStart());
    if (rows.isEmpty()) return List.of();
    return jdbc.query("""
        SELECT * FROM lp_price_range_item
        WHERE id = ? AND business_unit_type = ? AND current_flag = 1
        """, (rs, rowNum) -> range(rs, rows), id, s.businessUnit());
  }

  private FormalPriceReference range(ResultSet rs, List<RangeRow> rows) throws SQLException {
    List<FormalPriceReference.Field> fields = new ArrayList<>();
    int index = 0;
    for (RangeRow row : rows) {
      String rowKey = "ROW-" + row.id();
      int sort = ++index * 100;
      field(fields, "RANGE_ROW", rowKey, "RANGE_LOW", "区间下限", "DECIMAL",
          row.low(), null, true, false, sort + 10);
      field(fields, "RANGE_ROW", rowKey, "RANGE_HIGH", "区间上限", "DECIMAL",
          row.high(), null, false, false, sort + 20);
      field(fields, "RANGE_ROW", rowKey, "PRICE_EXCL_TAX", "不含税价", "DECIMAL",
          row.priceExclTax(), row.unit(), false, false, sort + 30);
      field(fields, "RANGE_ROW", rowKey, "PRICE_INCL_TAX", "含税价", "DECIMAL",
          row.priceInclTax(), row.unit(), false, false, sort + 40);
    }
    field(fields, "COMMON", "MAIN", "RANGE_BASIS", "区间依据", "TEXT",
        rs.getString("range_basis"), null, true, false, 10);
    field(fields, "COMMON", "MAIN", "FACTOR_CODE", "影响因素", "TEXT",
        rs.getString("factor_code"), null, false, false, 20);
    String summary = rows.size() + "段区间 · "
        + rows.get(0).low() + "～" + (rows.get(rows.size() - 1).high() == null
            ? "无穷" : rows.get(rows.size() - 1).high()) + text(rs.getString("unit"));
    return common(rs, RANGE, "RANGE", summary, fields, null);
  }

  private record RangeRow(
      long id, String low, String high, String priceExclTax, String priceInclTax, String unit) {}

  private List<FormalPriceReference> searchSettle(Scope s, String pattern) {
    return jdbc.query("""
        SELECT i.*, s.month AS pricing_month, s.buyer AS supplier_name,
               NULL AS supplier_code, NULL AS org_code, NULL AS unit,
               NULL AS tax_included, NULL AS tax_rate,
               NULL AS effective_from, NULL AS effective_to
        FROM lp_price_settle_item i JOIN lp_price_settle s ON s.id = i.settle_id
        WHERE i.business_unit_type = ? AND s.business_unit_type = ?
          AND (s.month IS NULL OR s.month = '' OR s.month <= ?)
          AND (i.material_code LIKE ? ESCAPE '\\\\' OR i.material_name LIKE ? ESCAPE '\\\\'
               OR i.model LIKE ? ESCAPE '\\\\')
        ORDER BY s.month DESC, i.id DESC LIMIT ?
        """, (rs, rowNum) -> settle(rs), s.businessUnit(), s.businessUnit(), s.monthText(),
        pattern, pattern, pattern, LIMIT_PER_TYPE);
  }

  private List<FormalPriceReference> querySettle(Scope s, Long id) {
    return jdbc.query("""
        SELECT i.*, s.month AS pricing_month, s.buyer AS supplier_name,
               NULL AS supplier_code, NULL AS org_code, NULL AS unit,
               NULL AS tax_included, NULL AS tax_rate,
               NULL AS effective_from, NULL AS effective_to
        FROM lp_price_settle_item i JOIN lp_price_settle s ON s.id = i.settle_id
        WHERE i.id = ? AND i.business_unit_type = ? AND s.business_unit_type = ?
          AND (s.month IS NULL OR s.month = '' OR s.month <= ?)
        """, (rs, rowNum) -> settle(rs), id, s.businessUnit(), s.businessUnit(), s.monthText());
  }

  private FormalPriceReference settle(ResultSet rs) throws SQLException {
    List<FormalPriceReference.Field> fields = new ArrayList<>();
    field(fields, "COMMON", "MAIN", "PLANNED_PRICE", "计划价", "DECIMAL",
        value(rs.getBigDecimal("planned_price")), null, false, false, 10);
    field(fields, "COMMON", "MAIN", "MARKUP_RATIO", "加价比例", "DECIMAL",
        value(rs.getBigDecimal("markup_ratio")), null, false, false, 20);
    field(fields, "COMMON", "MAIN", "BASE_SETTLE_PRICE", "基础结算价", "DECIMAL",
        value(rs.getBigDecimal("base_settle_price")), null, true, false, 30);
    field(fields, "COMMON", "MAIN", "LINKED_SETTLE_PRICE", "联动结算价", "DECIMAL",
        value(rs.getBigDecimal("linked_settle_price")), null, false, false, 40);
    BigDecimal price = firstDecimal(rs, "base_settle_price", "planned_price", "linked_settle_price");
    return new FormalPriceReference(SETTLE, rs.getLong("id"), "SETTLE_FIXED",
        rs.getString("material_code"), rs.getString("material_name"), rs.getString("model"),
        null, null, rs.getString("supplier_name"), null, null, null, null, null,
        summary(price, null), "结算期间 " + text(rs.getString("pricing_month")), fields);
  }

  private FormalPriceReference common(
      ResultSet rs, String source, String priceType, String summary,
      List<FormalPriceReference.Field> fields, String period) throws SQLException {
    LocalDate from = date(rs, "effective_from");
    LocalDate to = date(rs, "effective_to");
    String version = StringUtils.hasText(period) ? period
        : (from == null ? "当前有效" : from + (to == null ? "起" : " 至 " + to));
    return new FormalPriceReference(source, rs.getLong("id"), priceType,
        rs.getString("material_code"), rs.getString("material_name"), rs.getString("spec_model"),
        rs.getString("org_code"), rs.getString("supplier_code"), rs.getString("supplier_name"),
        rs.getString("unit"), nullableInteger(rs, "tax_included"), value(decimalIfPresent(rs, "tax_rate")),
        from, to, summary, version, fields);
  }

  private static Scope scope(String businessUnit, String org, String month) {
    String bu = CollaborationScope.requireBusinessUnit(businessUnit);
    String orgCode = CollaborationScope.requireText(org, "价格组织");
    YearMonth ym;
    try {
      ym = YearMonth.parse(CollaborationScope.requireText(month, "核算月份"));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("核算月份必须为yyyy-MM", exception);
    }
    return new Scope(bu, orgCode, ym.toString(), ym.atDay(1), ym.atEndOfMonth());
  }

  private static String normalizeType(String type, boolean optional) {
    if (!StringUtils.hasText(type)) {
      if (optional) return null;
      throw new IllegalArgumentException("价格类型不能为空");
    }
    String value = type.trim().toUpperCase(Locale.ROOT);
    if (!List.of("FIXED_PURCHASE", "LINKED", "RANGE", "SETTLE_FIXED").contains(value)) {
      throw new IllegalArgumentException("不支持的价格类型：" + type);
    }
    return value;
  }

  private static void field(
      List<FormalPriceReference.Field> fields, String section, String row, String code,
      String name, String type, String value, String unit, boolean required,
      boolean techInput, int sort) {
    fields.add(new FormalPriceReference.Field(
        section, row, code, name, type, value, unit, required, techInput, sort));
  }

  private static BigDecimal firstDecimal(ResultSet rs, String... names) throws SQLException {
    for (String name : names) {
      BigDecimal value = rs.getBigDecimal(name);
      if (value != null) return value;
    }
    return null;
  }

  private static LocalDate date(ResultSet rs, String name) throws SQLException {
    java.sql.Date value = rs.getDate(name);
    return value == null ? null : value.toLocalDate();
  }

  private static Integer nullableInteger(ResultSet rs, String name) throws SQLException {
    int value = rs.getInt(name);
    return rs.wasNull() ? null : value;
  }

  private static BigDecimal decimalIfPresent(ResultSet rs, String name) throws SQLException {
    try {
      return rs.getBigDecimal(name);
    } catch (SQLException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("not found")) return null;
      throw exception;
    }
  }

  private static String value(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static String summary(BigDecimal price, String unit) {
    return price == null ? "正式有效记录" : value(price) + (StringUtils.hasText(unit) ? " 元/" + unit : "");
  }

  private static String text(String value) {
    return value == null ? "" : value;
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record Scope(
      String businessUnit, String orgCode, String monthText,
      LocalDate monthStart, LocalDate monthEnd) {}
}
