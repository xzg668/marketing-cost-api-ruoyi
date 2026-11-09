package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.security.QuoteVisibilityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataModuleResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProductResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchRowResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 核算已确认但尚未分派的缺口直接进入工作台；读取列表不创建任务或 OA 待办。 */
@Repository
@lombok.extern.slf4j.Slf4j
public class TechnicalDataPendingProductQuery {
  // 原核算快照只提供待核对的范围，公共资料已补齐时只更新本次显示，不改写原快照。
  // 原有共享办理资料仍保留提交/审批约束；不能通过列表覆盖原办理人的状态。
  private static final String CURRENT_DRAWING_SOURCE = """
      SELECT v.id FROM lp_quote_bom_preparation_record p
      JOIN lp_quote_bom_supplement_version v ON v.id=p.electronic_source_version_id
        AND v.preparation_id=p.id AND v.oa_form_item_id=i.id AND v.active_flag=1
        AND BINARY v.period_month=BINARY w.period_month
        AND BINARY v.material_org_code=BINARY p.material_organization_code
        AND v.bom_source IN ('ELECTRONIC_DRAWING_PDF','ELECTRONIC_DRAWING_EXCEL')
      WHERE p.oa_form_item_id=i.id AND p.active_flag=1
        AND BINARY p.cost_period_month=BINARY w.period_month
        AND EXISTS (SELECT 1 FROM lp_electronic_drawing_source_node n WHERE n.supplement_version_id=v.id)
        AND NOT EXISTS (
          SELECT 1 FROM JSON_TABLE(w.technical_check_json, '$.check.sharedModules[*]' COLUMNS (
            module_type VARCHAR(40) PATH '$.moduleType')) s WHERE s.module_type='DRAWING_BOM')
      ORDER BY p.id DESC LIMIT 1
      """;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TechnicalDataSharedModuleQuery shared;
  private final TechnicalDataQuoteSourceReader sources;

  public TechnicalDataPendingProductQuery(JdbcTemplate jdbc, ObjectMapper json, TechnicalDataSharedModuleQuery shared,
      TechnicalDataQuoteSourceReader sources) {
    this.jdbc = jdbc;
    this.json = json;
    this.shared = shared;
    this.sources = sources;
  }

  /** 旧空任务沿用已接收的本产品、本月份图库。只投影未办理模块，不改任务或历史快照。 */
  public java.util.Map<Long, Long> currentDrawingSources(List<Long> productIds) {
    if (productIds.isEmpty()) return java.util.Map.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(productIds.size(), "?"));
    var result = new java.util.LinkedHashMap<Long, Long>();
    jdbc.query("""
        SELECT x.id product_id,v.id source_id FROM lp_quote_tech_product x
        JOIN lp_quote_tech_task t ON t.id=x.task_id AND t.active_flag=1
          AND t.task_status='UNASSIGNED' AND t.assignee_user_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_oa_recipient r WHERE r.task_id=t.id
            AND (r.active_flag=1 OR r.dispatch_status<>'REJECTED' OR r.latest_submission_id IS NOT NULL))
        JOIN lp_quote_tech_module m ON m.product_id=x.id AND m.module_type='DRAWING_BOM'
          AND m.required_flag=1 AND m.module_status='PENDING' AND m.current_version_id IS NULL
          AND m.assignee_user_id IS NULL AND m.entry_mode='NONE'
          AND m.requirement_reason_code IN ('DRAWING_SOURCE_PENDING','U9_ORIGINAL_NOT_FOUND')
        JOIN lp_quote_bom_preparation_record p ON p.oa_form_item_id=x.oa_form_item_id
          AND p.active_flag=1 AND BINARY p.cost_period_month=BINARY x.accounting_month
          AND BINARY p.price_org_code=BINARY t.applicable_org_code
        JOIN lp_quote_bom_supplement_version v ON v.id=p.electronic_source_version_id
          AND v.preparation_id=p.id AND v.oa_form_item_id=x.oa_form_item_id AND v.active_flag=1
          AND BINARY v.period_month=BINARY x.accounting_month
          AND BINARY v.material_org_code=BINARY p.material_organization_code
          AND v.bom_source IN ('ELECTRONIC_DRAWING_PDF','ELECTRONIC_DRAWING_EXCEL')
        WHERE x.active_flag=1 AND x.id IN (%s)
          AND EXISTS (SELECT 1 FROM lp_electronic_drawing_source_node n WHERE n.supplement_version_id=v.id)
        ORDER BY p.id
        """.formatted(placeholders), row -> {
      result.put(row.getLong("product_id"), row.getLong("source_id"));
    }, productIds.toArray());
    return result;
  }

  public TechnicalDataModuleResponse displayCurrentDrawing(TechnicalDataModuleResponse module, Long sourceId) {
    if (sourceId == null || !"DRAWING_BOM".equals(module.moduleType())) return module;
    return new TechnicalDataModuleResponse(module.id(), module.moduleType(), false, "DRAWING_SOURCE_AVAILABLE",
        "已有电子图库明细表，制造件原材料等核算资料单独检查", module.entryMode(), "NOT_REQUIRED", null, 0,
        module.rowVersion(), "AVAILABLE", "{\"sourceVersionId\":" + sourceId + "}", LocalDateTime.now(),
        module.assigneeUserId(), module.assigneeName());
  }

  private record Query(String sql, List<Object> args) {}

  private Query scope(String accessMode, String businessUnit, String month, String keyword, String oaNo) {
    return scope(accessMode, businessUnit, month, keyword, oaNo, null);
  }

  private Query scope(String accessMode, String businessUnit, String month, String keyword, String oaNo,
      Long itemId) {
    StringBuilder sql = new StringBuilder("""
        FROM lp_quote_costing_workspace w
        JOIN oa_form_item i ON i.id=w.oa_form_item_id
        JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.business_unit_type=BINARY w.business_unit_type
        WHERE i.deleted=0 AND f.deleted=0
          AND w.last_checked_at IS NOT NULL AND w.technical_check_json IS NOT NULL
          AND EXISTS (
            SELECT 1 FROM JSON_TABLE(w.technical_check_json, '$.check.modules[*]' COLUMNS (
              module_type VARCHAR(40) PATH '$.moduleType', required_flag INT PATH '$.required',
              reason_code VARCHAR(80) PATH '$.reasonCode')) m
            WHERE (m.required_flag=1 OR (m.module_type='PRICE' AND m.reason_code='PRICE_BASIS_UNCONFIRMED'))
              AND (m.module_type<>'DRAWING_BOM' OR (%s) IS NULL)
              AND NOT EXISTS (
              SELECT 1 FROM JSON_TABLE(w.technical_check_json, '$.check.sharedModules[*]' COLUMNS (
                module_type VARCHAR(40) PATH '$.moduleType', source_status VARCHAR(40) PATH '$.status')) s
              WHERE s.module_type=m.module_type AND s.source_status IN ('SUBMITTED','APPROVED')))
          AND NOT EXISTS (SELECT 1 FROM lp_quote_tech_task t
            WHERE t.oa_form_item_id=i.id AND BINARY t.accounting_month=BINARY w.period_month AND t.active_flag=1)
        """.formatted(CURRENT_DRAWING_SOURCE));
    sql.append(" AND ").append(QuoteVisibilityContext.formCondition("f"));
    List<Object> args = new ArrayList<>();
    if (!"ALL".equals(accessMode)) { sql.append(" AND f.business_unit_type=?"); args.add(businessUnit); }
    if (oaNo != null) { sql.append(" AND f.oa_no=?"); args.add(oaNo); }
    if (itemId != null) { sql.append(" AND i.id=?"); args.add(itemId); }
    if (month != null) { sql.append(" AND w.period_month=?"); args.add(month); }
    if (keyword != null) {
      sql.append(" AND (f.oa_no LIKE ? OR i.material_no LIKE ? OR i.product_name LIKE ? OR i.sunl_model LIKE ?)");
      for (int index = 0; index < 4; index++) args.add("%" + keyword + "%");
    }
    return new Query(sql.toString(), args);
  }

  public long count(String accessMode, String businessUnit, String month, String keyword, String oaNo) {
    return readPage(accessMode, businessUnit, month, keyword, oaNo, 0, 0).total();
  }

  public boolean hasPendingItem(String oaNo, long itemId) {
    Query query = scope("ALL", null, null, null, oaNo, itemId);
    return readPage(query, 0, 1, true).total() > 0;
  }

  public List<TechnicalDataWorkbenchRowResponse> page(String accessMode, String businessUnit,
      String month, String keyword, String oaNo, int offset, int size) {
    return readPage(accessMode, businessUnit, month, keyword, oaNo, offset, size).records();
  }

  public record Page(long total, List<TechnicalDataWorkbenchRowResponse> records) {}

  /** 先按当前来源移除已补齐产品，再计数、分页；一次请求只检查每个候选产品一次。 */
  public Page readPage(String accessMode, String businessUnit, String month, String keyword,
      String oaNo, int offset, int size) {
    if ("ASSIGNEE".equals(accessMode)) return new Page(0, List.of());
    return readPage(scope(accessMode, businessUnit, month, keyword, oaNo), offset, size, false);
  }

  private Page readPage(Query query, int offset, int size, boolean firstOnly) {
    long total = 0;
    List<TechnicalDataWorkbenchRowResponse> records = new ArrayList<>();
    for (int candidateOffset = 0; ; candidateOffset += 128) {
      var candidates = candidates(query, candidateOffset, 128);
      for (var row : candidates) {
        if (row.product().modules().stream().noneMatch(TechnicalDataModuleResponse::required)) continue;
        if (total >= offset && records.size() < size) records.add(row);
        total++;
        if (firstOnly) return new Page(total, List.copyOf(records));
      }
      if (candidates.size() < 128) return new Page(total, List.copyOf(records));
    }
  }

  private List<TechnicalDataWorkbenchRowResponse> candidates(Query query, int offset, int size) {
    List<Object> args = new ArrayList<>(query.args());
    args.add(offset); args.add(size);
    return jdbc.query("""
        SELECT i.id,i.seq,i.material_no,i.product_name,i.sunl_model,i.spec,i.annual_volume,
          f.oa_no,w.period_month,w.technical_check_json,(%s) AS current_drawing_source_id
        """.formatted(CURRENT_DRAWING_SOURCE) + query.sql() + " ORDER BY w.updated_at DESC,w.id DESC LIMIT ?,?", (row, index) -> {
      TechnicalDataSourceCheckResponse check;
      try {
        check = json.treeToValue(json.readTree(row.getString("technical_check_json")).get("check"),
            TechnicalDataSourceCheckResponse.class);
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("产品 " + row.getLong("id") + " 的补录检查记录无法读取，请重新核算", exception);
      }
      if (!java.util.Objects.equals(check.oaFormItemId(), row.getLong("id"))
          || !java.util.Objects.equals(check.accountingMonth(), row.getString("period_month"))) {
        throw new IllegalStateException("补录检查与当前产品、月份不一致，请重新检查资料");
      }
      check = current(check);
      Long drawingSourceId = row.getObject("current_drawing_source_id", Long.class);
      var currentShared = shared.current(row.getLong("id"), row.getString("period_month"), check.modules());
      check = new TechnicalDataSourceCheckResponse(check.oaFormItemId(), check.accountingMonth(), check.fingerprint(),
          check.checkedAt(), check.modules(), check.taskUpdateMessage(), currentShared);
      var modules = TechnicalDataSharedModuleQuery.applySharedSources(check.modules(), check.sharedModules()).stream().map(module ->
          "DRAWING_BOM".equals(module.moduleType()) && drawingSourceId != null
              ? new TechnicalDataModuleResponse(null, module.moduleType(), false, "DRAWING_SOURCE_AVAILABLE",
                  "已有电子图库明细表，制造件原材料等核算资料单独检查", null, "NOT_REQUIRED", null, 0, null,
                  "AVAILABLE", "{\"sourceVersionId\":" + drawingSourceId + "}", LocalDateTime.now(), null, null)
              : new TechnicalDataModuleResponse(
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

  private TechnicalDataSourceCheckResponse current(TechnicalDataSourceCheckResponse saved) {
    boolean mixedPriceCheck = saved.modules().stream().anyMatch(row -> "PRICE".equals(row.moduleType())
        && "PRICE_BASIS_UNCONFIRMED".equals(row.reasonCode()));
    if (!mixedPriceCheck && saved.modules().stream().noneMatch(row -> row.required() && !"DRAWING_BOM".equals(row.moduleType()))) return saved;
    try {
      var current = sources.preview(saved.oaFormItemId(), saved.accountingMonth());
      // 修正已核算的旧混合缺口投影；新结论必须来自当前权威来源，查询不写回历史。
      // 返回同次检查的完整指纹，使后续实际分派能验证同一组资料。
      if (mixedPriceCheck && current.modules().stream().anyMatch(row -> "PRICE".equals(row.moduleType())
          && (row.availability() == TechnicalDataAvailability.MISSING || row.availability() == TechnicalDataAvailability.AVAILABLE)))
        return current;
      // 只复核核算已经发现的缺口。查询失败或尚未核实不能当作已补齐，图库沿用精确来源绑定。
      var modules = saved.modules().stream().map(previous -> {
        if (!previous.required() || "DRAWING_BOM".equals(previous.moduleType())) return previous;
        return current.modules().stream().filter(next -> next.moduleType().equals(previous.moduleType())
            && !next.required() && (next.availability() == TechnicalDataAvailability.AVAILABLE
                || "PRICE".equals(next.moduleType()) && next.availability() == TechnicalDataAvailability.UNCONFIRMED
                    && "PRICE_BASIS_UNCONFIRMED".equals(next.reasonCode())))
            .findFirst().orElse(previous);
      }).toList();
      return new TechnicalDataSourceCheckResponse(saved.oaFormItemId(), saved.accountingMonth(),
          saved.fingerprint(), saved.checkedAt(), modules, saved.taskUpdateMessage(), saved.sharedModules());
    } catch (RuntimeException failure) {
      log.warn("待补录公共来源复核未完成，保留原检查：item={}, month={}",
          saved.oaFormItemId(), saved.accountingMonth(), failure);
      return saved;
    }
  }
}
