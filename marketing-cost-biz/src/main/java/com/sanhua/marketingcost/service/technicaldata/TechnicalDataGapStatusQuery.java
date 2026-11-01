package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前补录缺口的只读视图。原核算检查、冻结版本和历史价格结果均不改写。 */
@Service
public class TechnicalDataGapStatusQuery {
  public record Status(long taskId, long oaFormItemId, String accountingMonth, String status,
      List<String> requiredModules, List<String> pendingModules,
      List<String> resolvedPriceGapKeys, Integer remainingPriceGapCount) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public TechnicalDataGapStatusQuery(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<Status> forForm(long formId, Long itemId, String month) {
    var rows = jdbc.queryForList("""
        SELECT t.id task_id,t.oa_form_item_id,t.accounting_month,t.applicable_org_code,
          t.task_status,p.product_status,m.module_type,m.module_status,v.version_status,
          r.todo_status,r.return_reason,s.submission_status,
          COALESCE(s.source_form_version,0)=COALESCE(d.source_version,0) current_form,
          CASE WHEN m.module_type='PRICE' THEN v.price_items_json END price_json,
          CASE WHEN m.module_type='PRICE' THEN w.technical_check_json END technical_check_json,
          CASE WHEN m.module_type='PRICE' THEN w.gap_count END saved_gap_count
        FROM lp_quote_tech_task t
        JOIN lp_quote_tech_product p ON p.task_id=t.id AND p.active_flag=1
        JOIN lp_quote_tech_module m ON m.product_id=p.id AND m.required_flag=1
        LEFT JOIN lp_quote_tech_data_version v ON v.id=m.current_version_id AND v.product_id=p.id
        LEFT JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id AND r.active_flag=1
          AND r.assignee_user_id=m.assignee_user_id
        LEFT JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
          AND s.task_id=t.id AND s.product_id=p.id AND s.recipient_id=r.id
        LEFT JOIN lp_oa_quote_document d ON d.oa_form_id=t.oa_form_id
        LEFT JOIN lp_quote_costing_workspace w ON w.oa_form_item_id=t.oa_form_item_id
          AND w.period_month=t.accounting_month AND w.business_unit_type=t.business_unit_type
        WHERE t.oa_form_id=? AND (? IS NULL OR t.oa_form_item_id=?)
          AND t.accounting_month=? AND t.active_flag=1
        ORDER BY t.id,m.id
        """, formId, itemId, itemId, month);
    Map<Long, List<Map<String, Object>>> products = new LinkedHashMap<>();
    for (var row : rows) products.computeIfAbsent(number(row, "task_id"), ignored -> new ArrayList<>()).add(row);
    return products.values().stream().map(this::status).toList();
  }

  private Status status(List<Map<String, Object>> rows) {
    var first = rows.getFirst();
    boolean returned = rows.stream().anyMatch(row -> "RETURNED".equals(row.get("module_status"))
        || "PARTIALLY_RETURNED".equals(row.get("task_status"))
        || "OPEN".equals(row.get("todo_status")) && row.get("return_reason") != null);
    var required = rows.stream().map(row -> text(row, "module_type")).distinct()
        .sorted(Comparator.comparingInt(TechnicalDataModuleType::orderOf)).toList();
    // 一旦产品被退回，整份补录均待重新提交；不会使用同产品其他人的旧提交掩盖退回。
    var pending = returned ? required : rows.stream().filter(row -> !submitted(row))
        .map(row -> text(row, "module_type")).distinct()
        .sorted(Comparator.comparingInt(TechnicalDataModuleType::orderOf)).toList();
    String status = returned ? "RETURNED" : pending.isEmpty() ? "SUBMITTED" : "PENDING";
    var prices = rows.stream().filter(row -> "PRICE".equals(row.get("module_type"))).findFirst().orElse(null);
    List<String> resolved = new ArrayList<>();
    Integer remaining = null;
    if (prices != null) {
      var evidence = read(prices.get("technical_check_json")).path("evidence").path("PRICE").path("gaps");
      if (evidence.isArray()) {
        // 技术缺口清单不包含供应商比率等财务事项，不能随补录一起隐藏这些额外缺口。
        remaining = prices.get("saved_gap_count") instanceof Number count
            ? Math.max(count.intValue(), evidence.size()) : evidence.size();
        Set<String> fixed = new HashSet<>();
        if (!returned && submitted(prices)) {
          for (var price : read(prices.get("price_json")).path("items")) {
            if ("FIXED".equals(price.path("entryMode").asText()) && price.path("unitPrice").isNumber()
                && price.path("unitPrice").decimalValue().signum() >= 0
                && text(first, "applicable_org_code").equals(price.path("organizationCode").asText())) {
              fixed.add(price.path("materialNo").asText());
            }
          }
        }
        for (var gap : evidence) {
          if (gap.isArray() && gap.size() >= 3 && "MISSING_PRICE".equals(gap.get(1).asText())
              && Set.of("", "MISSING").contains(gap.get(2).asText()) && fixed.contains(gap.get(0).asText())) {
            resolved.add(gapKey(gap.get(0).asText(), gap.get(1).asText(), gap.get(2).asText()));
            remaining--;
          }
        }
      }
    }
    return new Status(number(first, "task_id"), number(first, "oa_form_item_id"),
        text(first, "accounting_month"), status, required, pending, List.copyOf(resolved), remaining);
  }

  private boolean submitted(Map<String, Object> row) {
    // 发起退回但 OA 尚未确认时保留已提交结论；真正退回后 OPEN/RETURNED 会恢复缺口。
    return Set.of("SUBMITTED", "APPROVED").contains(text(row, "module_status"))
        && Set.of("SUBMITTED", "APPROVED").contains(text(row, "version_status"))
        && Set.of("SUBMITTED", "DONE", "RETURN_PENDING").contains(text(row, "todo_status"))
        && Set.of("SENT", "APPROVED").contains(text(row, "submission_status"))
        && (Boolean.TRUE.equals(row.get("current_form")) || row.get("current_form") instanceof Number n && n.intValue() == 1);
  }

  private JsonNode read(Object value) {
    if (value == null) return json.createObjectNode();
    try { return json.readTree(value.toString()); }
    catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("补录缺口记录无法读取，请核实原资料", error);
    }
  }

  private static String gapKey(String material, String type, String reason) {
    return material + "|" + type + "|" + reason;
  }
  private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
  private static String text(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
}
