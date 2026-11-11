package com.sanhua.marketingcost.service.costing;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只核对来源记录是否存在，不另做价格选择，也不读月价代替原始价格来源。 */
@Service
public class CostingPriceSourceReview {
  public record Check(String source, int records, String note) {}
  public record Review(boolean absent, List<Check> sources, String reason) {}
  private record Source(String table, String codeColumn, String label, boolean ods) {}
  private static final List<Source> SOURCES = List.of(
      new Source("lp_price_fixed_item", "material_code", "固定价及历史结算价", false),
      new Source("lp_price_linked_item", "material_code", "联动价", false),
      new Source("lp_price_range_item", "material_code", "区间价", false),
      new Source("lp_price_range_factor_rule", "material_code", "行情区间规则", false),
      new Source("lp_settlement_price", "material_code", "结算价／结算联动价", false),
      new Source("ods_sy_v_mm_material_order_price_cg05_df", "material_code", "订单价", true),
      new Source("ods_sy_v_mm_material_jt_price_cg05_df", "material_code", "阶梯价", true),
      new Source("lp_invoice_price", "material_code", "发票价", false),
      new Source("lp_inquiry_rule", "material_key", "询价", false),
      new Source("lp_household_inquiry_price", "material_code", "家用询价", false),
      new Source("lp_scrap_price", "material_code", "废料价", false),
      new Source("lp_material_monthly_price", "material_code", "当月月价（已采用结果）", false));
  private final NamedParameterJdbcTemplate sql;
  public CostingPriceSourceReview(JdbcTemplate jdbc) { sql = new NamedParameterJdbcTemplate(jdbc); }

  @Transactional(readOnly = true)
  public Map<String, Review> read(String unit, String month, Collection<String> materialCodes) {
    var codes = materialCodes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    if (codes.isEmpty()) return Map.of();
    Map<String, List<Check>> checks = new LinkedHashMap<>(); codes.forEach(code -> checks.put(key(code), new ArrayList<>()));
    for (var source : SOURCES) {
      Map<String, List<Map<String, Object>>> records = new HashMap<>();
      for (int start = 0; start < codes.size(); start += 256) {
        String scope = source.ods()
            ? "company_name IN ('浙江三花商用制冷有限公司','浙江三花板换科技有限公司')"
            : "(business_unit_type=:unit OR business_unit_type IS NULL OR TRIM(business_unit_type)='')";
        if (source.table().equals("lp_material_monthly_price")) scope += " AND pricing_month=:month";
        for (var row : sql.queryForList("SELECT " + columns(source) + " FROM " + source.table() + " WHERE " + source.codeColumn()
            + " IN (:codes) AND " + scope, Map.of("codes", codes.subList(start, Math.min(start + 256, codes.size())), "unit", unit, "month", month))) {
          records.computeIfAbsent(key(text(row.get(source.codeColumn()))), ignored -> new ArrayList<>()).add(row);
        }
      }
      for (var code : codes) {
        var rows = records.getOrDefault(key(code), List.of());
        checks.get(key(code)).add(new Check(source.label(), rows.size(), describe(rows, source, unit)));
      }
    }
    Map<String, Review> result = new LinkedHashMap<>();
    checks.forEach((code, values) -> result.put(code, summarize(values)));
    return result;
  }

  static Review summarize(List<Check> values) {
      var found = values.stream().filter(v -> v.records() > 0).toList();
      String reason = found.isEmpty() ? "当前业务范围各价格来源均无该料号记录，请财务导入价格"
          : "已有价格记录，本次未取到可用价格。" + found.stream()
              .map(v -> v.source() + "：" + v.records() + "条" + (v.note().isBlank() ? "" : "（" + v.note() + "）"))
              .collect(java.util.stream.Collectors.joining("；"))
              + "。请核实已有记录的适用条件及本次取价检查结果";
      return new Review(found.isEmpty(), List.copyOf(values), reason);
  }

  private static String columns(Source source) {
    return switch (source.table()) {
      case "lp_price_fixed_item" -> "material_code,org_code,pricing_month,source_kind,technical_publication_status,source_type,fixed_price,effective_from";
      case "lp_price_linked_item" -> "material_code,org_code,pricing_month,source_kind,technical_publication_status,rule_status,deleted,effective_from";
      case "lp_price_range_item" -> "material_code,org_code,current_flag,effective_from";
      case "lp_settlement_price" -> "material_code,price_month,active";
      case "lp_invoice_price" -> "material_code,org_code";
      case "lp_inquiry_rule" -> "material_key,price_month";
      case "lp_scrap_price" -> "material_code,price_month";
      case "lp_material_monthly_price" -> "material_code,pricing_month,disabled";
      default -> source.codeColumn() + (source.ods() ? ",company_name" : "");
    };
  }

  private static String describe(List<Map<String, Object>> rows, Source source, String unit) {
    if (rows.isEmpty()) return "无记录";
    Set<String> details = new LinkedHashSet<>();
    Set<String> orgs = new LinkedHashSet<>(), periods = new LinkedHashSet<>();
    for (var row : rows) {
      var org = text(row.get("org_code")); if (!org.isBlank()) orgs.add(org);
      var company = text(row.get("company_name")); if (!company.isBlank()) orgs.add(company);
      String month = text(row.getOrDefault("pricing_month", row.get("price_month"))); if (!month.isBlank()) periods.add(month);
      if (one(row.get("deleted")) || one(row.get("disabled")) || zero(row.get("active")) || zero(row.get("current_flag"))) details.add("包含已停用或历史记录");
      String status = text(row.get("rule_status"));
      if (!status.isBlank() && !Set.of("LEGACY", "PUBLISHED").contains(status)) details.add("包含尚未发布的价格版本");
      if ("TECH_SUPPLEMENTAL".equals(row.get("source_kind")) && !"AVAILABLE".equals(row.get("technical_publication_status"))) details.add("补录价格尚未生效");
      if (source.ods() && !"COMMERCIAL".equals(unit)) details.add("该采购来源当前仅适用商用核算");
      if (source.table().equals("lp_material_monthly_price")) details.add("由原始价格来源首次采用保存，本次未命中时需核对其适用范围");
      if (source.table().equals("lp_price_fixed_item") && Set.of("MAKE", "SCRAP").contains(text(row.get("source_type")))) details.add("包含制造件或废料参考价，不能直接当作固定采购价");
      if (source.table().equals("lp_price_fixed_item") && Set.of("PURCHASE", "PURCHASE_FIXED").contains(text(row.get("source_type"))) && row.get("fixed_price") == null) details.add("固定采购单价尚未填写");
      if (source.table().equals("lp_price_linked_item") && !"".equals(text(row.get("effective_from")))) {
        details.add("须核对价格月份、生效期间及财务行情档位");
      }
    }
    if (!orgs.isEmpty()) details.add("记录组织：" + String.join("、", orgs));
    if (!periods.isEmpty()) details.add("记录月份：" + String.join("、", periods));
    return String.join("；", details);
  }
  private static boolean one(Object v) { return "1".equals(text(v)) || Boolean.TRUE.equals(v); }
  private static boolean zero(Object v) { return v != null && ("0".equals(text(v)) || Boolean.FALSE.equals(v)); }
  private static String text(Object v) { return v == null ? "" : v.toString(); }
  private static String key(String v) { return v.trim().toUpperCase(Locale.ROOT); }
}
