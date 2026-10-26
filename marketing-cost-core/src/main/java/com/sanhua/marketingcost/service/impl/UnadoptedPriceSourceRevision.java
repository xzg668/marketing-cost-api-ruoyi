package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mutable dependencies of actual unadopted price candidates; never called for adopted inputs. */
final class UnadoptedPriceSourceRevision {
  record ScrapDependencies(String revision, Set<String> materialCodes) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  UnadoptedPriceSourceRevision(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** Refresh missing candidates on latest-partition replacement; adopted monthly inputs never call this. */
  String ods(String unit,Collection<String> materials) {
    if(!"COMMERCIAL".equals(unit))return "NOT_APPLICABLE";
    var observed=new ArrayList<Object>();
    for(String table:List.of("ods_sy_v_mm_material_order_price_cg05_df","ods_sy_v_mm_material_jt_price_cg05_df")) {
      String partition=jdbc.queryForObject("SELECT MAX(dt) FROM "+table,String.class);
      observed.add(table);observed.add(partition);
      if(partition==null)continue;
      if(materials.isEmpty()) { observed.add(jdbc.queryForList("CHECKSUM TABLE "+table));continue; }
      var codes=new ArrayList<>(new TreeSet<>(materials));
      for(int start=0;start<codes.size();start+=256) {
        var batch=codes.subList(start,Math.min(start+256,codes.size()));
        var args=new ArrayList<Object>();args.add(partition);args.addAll(batch);
        observed.add(jdbc.queryForList("SELECT * FROM "+table+" WHERE dt=? AND material_code IN ("+placeholders(batch.size())+") ORDER BY id",args.toArray()));
      }
    }
    return hash(observed);
  }

  ScrapDependencies scrapDependencies(String unit, Collection<String> materials) {
    var rows = materials.isEmpty()
        ? jdbc.queryForList("SELECT * FROM lp_material_scrap_ref WHERE business_unit_type=? ORDER BY id", unit)
        : scoped("lp_material_scrap_ref", "material_code", materials, "business_unit_type=?", unit);
    var codes = new TreeSet<String>();
    rows.forEach(row -> { var code = value(row, "scrap_code"); if (code != null) codes.add(code.toString()); });
    return new ScrapDependencies(hash(rows), Set.copyOf(codes));
  }

  String legacyFormula(String unit, String month, Collection<Long> rules) {
    var observed = new ArrayList<Object>();
    // The active catalog also supplies aliases and nested definitions used by normalization.
    var definitions = jdbc.queryForList("SELECT * FROM lp_price_variable WHERE status='active' AND (business_unit_type=? OR business_unit_type IS NULL) ORDER BY id", unit);
    observed.add(definitions);
    var bindings = scoped("lp_price_variable_binding", "linked_item_id", rules,
        "expiry_date IS NULL AND deleted=0");
    observed.add(bindings);
    observed.add(jdbc.queryForList("SELECT * FROM lp_row_local_placeholder WHERE status='active' AND deleted=0 ORDER BY id"));
    var identities = new TreeSet<Long>();
    bindings.forEach(row -> addId(identities, value(row, "factor_identity_id")));
    for (var definition : definitions) {
      var parameters = value(definition, "resolver_params");
      if (parameters == null || parameters.toString().isBlank()) continue;
      try {
        var id = json.readTree(parameters.toString()).get("factorIdentityId");
        if (id != null && !id.isNull()) addId(identities, id.asText());
      } catch (java.io.IOException invalid) {
        // Invalid catalog content must still change the revision so preparation can report its error.
      }
    }
    observed.add(scoped("lp_factor_identity", "id", identities, "business_unit_type=?", unit));
    observed.add(scoped("lp_factor_monthly_price", "factor_identity_id", identities,
        "scheme_code='LEGACY' AND status='ACTIVE' AND price_month<=?", month));
    return hash(observed);
  }

  String technical(String unit, Collection<String> materials) {
    var observed = new ArrayList<Object>();
    var claims = scoped("lp_quote_tech_price_claim", "material_code", materials, "business_unit_type=?", unit);
    observed.add(claims);
    var ownerIds = ids(claims, "owner_module_id");
    var owners = scoped("lp_quote_tech_module", "id", ownerIds, null); observed.add(owners);
    var productIds = ids(owners, "product_id");
    var products = scoped("lp_quote_tech_product", "id", productIds, null); observed.add(products);
    var modules = scoped("lp_quote_tech_module", "product_id", productIds, null); observed.add(modules);
    var tasks = scoped("lp_quote_tech_task", "id", ids(products, "task_id"), null); observed.add(tasks);
    var versions = ids(modules, "current_version_id"); versions.addAll(ids(products, "effective_version_id"));
    observed.add(scoped("lp_quote_tech_data_version", "id", versions, null));
    for (String table : List.of("lp_quote_tech_package_item", "lp_quote_tech_aux_item", "lp_quote_tech_salary_item"))
      observed.add(scoped(table, "version_id", versions, null));
    var flows = ids(tasks, "oa_flow_id");
    observed.add(scoped("lp_oa_technical_flow", "id", flows, null));
    if (!flows.isEmpty()) {
      String sql = "SELECT t.oa_flow_id,t.id task_id,r.id recipient_id,s.id submission_id,s.technical_version_id,s.content_fingerprint "
          + "FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id AND r.active_flag=1 "
          + "JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id "
          + "WHERE t.active_flag=1 AND r.todo_status IN ('SUBMITTED','DONE') AND s.submission_status IN ('SENT','APPROVED') "
          + "AND t.oa_flow_id IN (" + placeholders(flows.size()) + ") ORDER BY t.oa_flow_id,t.id,r.id";
      observed.add(jdbc.queryForList(sql, flows.toArray()));
    }
    observed.add(scoped("lp_material_master_raw", "material_code", materials, "active_flag=1"));
    return hash(observed);
  }

  private List<Map<String, Object>> scoped(String table, String column, Collection<?> values,
      String condition, Object... conditions) {
    if (values.isEmpty()) return List.of();
    var parameters = new ArrayList<Object>(values); parameters.addAll(Arrays.asList(conditions));
    return jdbc.queryForList("SELECT * FROM " + table + " WHERE " + column + " IN (" + placeholders(values.size()) + ")"
        + (condition == null ? "" : " AND " + condition) + " ORDER BY " + (table.equals("lp_quote_tech_price_claim") ? "material_code" : "id"),
        parameters.toArray());
  }
  private static TreeSet<Long> ids(List<Map<String, Object>> rows, String key) {
    var result = new TreeSet<Long>(); rows.forEach(row -> addId(result, value(row, key))); return result;
  }
  private static void addId(Set<Long> ids, Object value) { if (value != null) ids.add(Long.valueOf(value.toString())); }
  private static String placeholders(int size) { return String.join(",", Collections.nCopies(size, "?")); }
  private static Object value(Map<String, Object> row, String key) {
    for (var entry : row.entrySet()) if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
    return null;
  }
  private static String hash(Object value) {
    var result = new StringBuilder(); canonical(result, value); return MonthlyPriceDocumentCodec.sha256(result.toString());
  }
  private static void canonical(StringBuilder out, Object value) {
    if (value instanceof Map<?, ?> map) {
      var sorted = new TreeMap<String, Object>(); map.forEach((key, field) -> sorted.put(key.toString(), field));
      out.append('{'); sorted.forEach((key, field) -> { canonical(out, key); canonical(out, field); }); out.append('}');
    } else if (value instanceof Collection<?> list) {
      out.append('['); list.forEach(field -> canonical(out, field)); out.append(']');
    } else {
      String text = value == null ? "NULL" : value.toString(); out.append(text.length()).append(':').append(text);
    }
  }
}
