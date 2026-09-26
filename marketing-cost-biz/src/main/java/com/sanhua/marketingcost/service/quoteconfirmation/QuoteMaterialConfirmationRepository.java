package com.sanhua.marketingcost.service.quoteconfirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 资料确认记录与发送占用；唯一键对应一轮真实 OA 资料待办。 */
@Repository
public class QuoteMaterialConfirmationRepository {
  public record Confirmation(String id, long formId, long formVersion, String workItemId,
      long actorId, String requestKey, String status, String month, List<ProductCostingResult> checks, ObjectNode request, OaWorkflowResult result) {}
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final ObjectMapper json;

  public QuoteMaterialConfirmationRepository(JdbcTemplate jdbc, OaMessageCodec codec, ObjectMapper json) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.json = json;
  }

  public void lockForm(long formId) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? AND deleted=0 FOR UPDATE", Long.class, formId);
  }

  public Confirmation find(long formId, long formVersion, String workItemId) {
    return one("oa_form_id=? AND form_version=? AND material_work_item_id=?", formId, formVersion, workItemId);
  }

  public Confirmation find(String id) { return one("id=?", id); }

  private Confirmation one(String where, Object... parameters) {
    var rows = jdbc.query("SELECT * FROM lp_oa_material_confirmation WHERE " + where,
        (row, index) -> new Confirmation(row.getString("id"), row.getLong("oa_form_id"),
            row.getLong("form_version"), row.getString("material_work_item_id"), row.getLong("actor_user_id"),
            row.getString("request_key"), row.getString("status"), row.getString("accounting_month"),
            json.convertValue(codec.read(row.getString("readiness_json")), new TypeReference<List<ProductCostingResult>>() {}), (ObjectNode) codec.read(row.getString("request_json")),
            row.getString("result_json") == null ? null
                : json.convertValue(codec.read(row.getString("result_json")), OaWorkflowResult.class)), parameters);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void prepare(String id, long formId, long formVersion, String workItemId, long actorId,
      String employeeNo, String requestKey, String month, ObjectNode request, Object readiness) {
    jdbc.update("""
        INSERT INTO lp_oa_material_confirmation(id,oa_form_id,form_version,material_work_item_id,
          actor_user_id,employee_no,request_key,accounting_month,status,request_json,readiness_json)
        VALUES(?,?,?,?,?,?,?,?,'PREPARED',?,?)
        """, id, formId, formVersion, workItemId, actorId, employeeNo, requestKey, month,
        codec.write(request), codec.write(readiness));
  }

  public void retryRejected(String id, String requestKey, long actorId, String employeeNo,
      String month, ObjectNode request, Object readiness) {
    int changed = jdbc.update("""
        UPDATE lp_oa_material_confirmation SET status='PREPARED',request_key=?,actor_user_id=?,employee_no=?,
          accounting_month=?,request_json=?,readiness_json=?,result_json=NULL
        WHERE id=? AND status IN ('REJECTED','NOT_SENT')
        """, requestKey, actorId, employeeNo, month, codec.write(request), codec.write(readiness), id);
    if (changed != 1) throw new IllegalStateException("资料确认状态已变化，请刷新后核实原请求");
  }

  public void notSent(String id, OaWorkflowResult result) {
    jdbc.update("UPDATE lp_oa_material_confirmation SET status='NOT_SENT',result_json=? WHERE id=? AND status='PREPARED'",
        codec.write(result), id);
  }

  public boolean claim(String id) {
    return jdbc.update("UPDATE lp_oa_material_confirmation SET status='SENDING' WHERE id=? AND status='PREPARED'", id) == 1;
  }

  public void received(String id, OaWorkflowResult result) {
    jdbc.update("UPDATE lp_oa_material_confirmation SET status=?,result_json=? WHERE id=? AND status='SENDING'",
        result.status().name(), codec.write(result), id);
  }

  public List<Long> activeItems(long formId) {
    return jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? AND deleted=0 ORDER BY id", Long.class, formId);
  }

  public boolean hasSupplement(long formId) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_quote_tech_task WHERE oa_form_id=?)
        """, Boolean.class, formId));
  }

  public int pendingApprovals(long formId, long formVersion) {
    // 一经正式分派，即使公共来源后来补齐，也须明确完成或取消原技术分支。
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM lp_quote_tech_task t WHERE t.oa_form_id=? AND t.active_flag=1
          AND t.task_status<>'UNASSIGNED' AND (
            t.task_status<>'APPROVED' OR t.review_status<>'PASSED'
            OR EXISTS(SELECT 1 FROM lp_quote_tech_product p WHERE p.task_id=t.id AND p.active_flag=1
              AND (p.effective_version_id IS NULL OR p.product_status<>'APPROVED'))
            OR EXISTS(SELECT 1 FROM lp_quote_tech_oa_recipient r
              LEFT JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
              WHERE r.task_id=t.id AND r.active_flag=1
                AND (r.todo_status<>'DONE' OR s.submission_status<>'APPROVED'
                  OR COALESCE(s.source_form_version,0)<>?)))
        """, Integer.class, formId, formVersion);
  }
}
