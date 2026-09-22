package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataModuleResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchRowResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 核算已确认但尚未分派的缺口直接进入工作台；读取列表不创建任务或 OA 待办。 */
@Repository
public class TechnicalDataPendingProductQuery {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TechnicalDataPendingProductQuery(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private record Query(String sql, List<Object> args) {}

  private Query scope(String accessMode, String businessUnit, String month, String keyword) {
    StringBuilder sql = new StringBuilder("""
        FROM lp_quote_costing_workspace w
        JOIN oa_form_item i ON i.id=w.oa_form_item_id
        JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.business_unit_type=BINARY w.business_unit_type
        WHERE i.deleted=0 AND f.deleted=0
          AND w.last_checked_at IS NOT NULL AND w.technical_check_json IS NOT NULL
          AND EXISTS (
            SELECT 1 FROM JSON_TABLE(w.technical_check_json, '$.check.modules[*]' COLUMNS (
              module_type VARCHAR(40) PATH '$.moduleType', required_flag INT PATH '$.required')) m
            WHERE m.required_flag=1 AND NOT EXISTS (
              SELECT 1 FROM JSON_TABLE(w.technical_check_json, '$.check.sharedModules[*]' COLUMNS (
                module_type VARCHAR(40) PATH '$.moduleType', source_status VARCHAR(40) PATH '$.status')) s
              WHERE s.module_type=m.module_type AND s.source_status='APPROVED'))
          AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_task t
            WHERE t.oa_form_item_id=i.id AND BINARY t.accounting_month=BINARY w.period_month AND t.active_flag=1)
        """);
    List<Object> args = new ArrayList<>();
    if (!"ALL".equals(accessMode)) { sql.append(" AND f.business_unit_type=?"); args.add(businessUnit); }
    if (month != null) { sql.append(" AND w.period_month=?"); args.add(month); }
    if (keyword != null) {
      sql.append(" AND (f.oa_no LIKE ? OR i.material_no LIKE ? OR i.product_name LIKE ? OR i.sunl_model LIKE ?)");
      for (int index = 0; index < 4; index++) args.add("%" + keyword + "%");
    }
    return new Query(sql.toString(), args);
  }

  public long count(String accessMode, String businessUnit, String month, String keyword) {
    if ("ASSIGNEE".equals(accessMode)) return 0;
    Query query = scope(accessMode, businessUnit, month, keyword);
    return jdbc.queryForObject("SELECT COUNT(*) " + query.sql(), Long.class, query.args().toArray());
  }

  public List<TechnicalDataWorkbenchRowResponse> page(String accessMode, String businessUnit,
      String month, String keyword, int offset, int size) {
    if ("ASSIGNEE".equals(accessMode) || size <= 0) return List.of();
    Query query = scope(accessMode, businessUnit, month, keyword);
    List<Object> args = new ArrayList<>(query.args());
    args.add(offset); args.add(size);
    return jdbc.query("""
        SELECT i.id,i.seq,i.material_no,i.product_name,i.sunl_model,i.spec,i.annual_volume,
          f.oa_no,w.period_month,w.technical_check_json
        """ + query.sql() + " ORDER BY w.updated_at DESC,w.id DESC LIMIT ?,?", (row, index) -> {
      TechnicalDataSourceCheckResponse check;
      try {
        check = json.treeToValue(json.readTree(row.getString("technical_check_json")).get("check"),
            TechnicalDataSourceCheckResponse.class);
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("产品 " + row.getLong("id") + " 的补录检查记录无法读取，请重新核算", exception);
      }
      var modules = check.modules().stream().map(module -> new TechnicalDataModuleResponse(
          null, module.moduleType(), module.required(), module.reasonCode(), module.reason(), null,
          module.required() ? "PENDING" : "NOT_REQUIRED", null, 0, null, module.availability().name(),
          module.sourceReference(), module.checkedAt(), null, null)).toList();
      var product = new TechnicalDataProductResponse(null, row.getLong("id"), row.getInt("seq"),
          row.getString("material_no"), row.getString("product_name"), row.getString("sunl_model"),
          row.getString("spec"), row.getBigDecimal("annual_volume"), null, row.getString("oa_no"),
          row.getString("period_month"), null, check.fingerprint(), "UNASSIGNED", null, null, null,
          null, null, modules, null, null);
      return new TechnicalDataWorkbenchRowResponse(null, null, row.getString("oa_no"),
          row.getString("period_month"), null, null, "UNASSIGNED", null, null, null,
          List.of(), List.of(), product, check);
    }, args.toArray());
  }
}
