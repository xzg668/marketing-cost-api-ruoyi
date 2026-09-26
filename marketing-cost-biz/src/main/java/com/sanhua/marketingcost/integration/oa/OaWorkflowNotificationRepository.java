package com.sanhua.marketingcost.integration.oa;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 通知序号、去重范围及报价员办理信息由本地业务记录确定，不再要求 OA 提供待办列表。 */
@Repository
public class OaWorkflowNotificationRepository {
  public record Flow(long id, long appliedVersion, String state) {}
  public record Document(long id, long sourceVersion, String businessUnit) {}
  public record Quoter(long userId, String employeeNo) {}
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;

  public OaWorkflowNotificationRepository(JdbcTemplate jdbc, OaMessageCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }
  public void lockForm(long formId) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, formId);
  }
  public Flow lock(OaPeer peer, String requestId) {
    jdbc.update("""
        INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,active_work_items_json)
        VALUES(?,?,?,JSON_ARRAY()) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), requestId);
    return jdbc.queryForObject("""
        SELECT id,applied_version,state FROM lp_oa_workflow_state
        WHERE source_system=? AND environment=? AND workflow_request_id=? FOR UPDATE
        """, (r, n) -> new Flow(r.getLong("id"), r.getLong("applied_version"), r.getString("state")),
        peer.sourceSystem(), peer.environment(), requestId);
  }
  public Document document(OaPeer peer, String requestId) {
    var rows = jdbc.query("""
        SELECT f.id,d.source_version,f.business_unit_type
        FROM lp_oa_quote_document d JOIN oa_form f ON f.id=d.oa_form_id AND f.deleted=0
        WHERE d.source_system=? AND d.environment=? AND d.external_document_id=?
        """, (r, n) -> new Document(r.getLong("id"), r.getLong("source_version"), r.getString("business_unit_type")),
        peer.sourceSystem(), peer.environment(), requestId);
    return rows.isEmpty() ? null : rows.getFirst();
  }
  public void associate(Flow flow, long messageId, String scopeHash) {
    jdbc.update("INSERT INTO lp_oa_workflow_notification(flow_id,sequence_no,message_id,semantic_hash) VALUES(?,?,?,?)",
        flow.id(), flow.appliedVersion() + 1, messageId, scopeHash);
  }
  public void complete(long messageId, String reply) {
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='PROCESSED',result_json=?,error_code=NULL,
          error_message=NULL,processed_at=NOW(3) WHERE id=?
        """, reply, messageId);
  }

  /** 技术审批后的报价员取实际 I02 操作人；成本退回取原成本提交人。 */
  public Quoter quoter(long formId, Long resultOperator) {
    List<Long> ids = resultOperator == null
        ? jdbc.queryForList("SELECT actor_user_id FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I02' AND status='SUCCESS' ORDER BY created_at DESC,id DESC LIMIT 1", Long.class, formId)
        : List.of(resultOperator);
    if (ids.isEmpty()) {
      // 已办理历史单可能早于原生 I02；沿用其已经记录的报价员身份，不能猜管理员。
      ids = jdbc.queryForList("SELECT DISTINCT finance_user_id FROM lp_oa_technical_flow WHERE oa_form_id=? AND finance_user_id IS NOT NULL", Long.class, formId);
    }
    if (ids.size() != 1) throw conflict("QUOTER_NOT_FOUND", "本单缺少明确的报价员分派或成本提交记录");
    var users = jdbc.query("SELECT user_id,employee_no FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0' AND employee_no IS NOT NULL AND employee_no<>''",
        (r, n) -> new Quoter(r.getLong("user_id"), r.getString("employee_no")), ids.getFirst());
    if (users.isEmpty()) throw conflict("QUOTER_NOT_FOUND", "原报价员账号不存在、已停用或未维护工号");
    return users.getFirst();
  }

  public void applied(Flow flow, Document document, String state, String reason, Quoter quoter) {
    // LOCAL 前缀明确这是报价系统的资料办理轮次键，用于 I06 去重，不是 OA 待办 ID。
    Object items = quoter == null ? List.of() : List.of(Map.of(
        "workItemId", "LOCAL-" + flow.id() + "-" + (flow.appliedVersion() + 1),
        "nodeRole", "MATERIAL_REVIEW".equals(state) ? "MATERIAL" : "COSTING",
        "employeeNo", quoter.employeeNo()));
    jdbc.update("""
        UPDATE lp_oa_workflow_state SET applied_version=?,form_version=?,observed_form_version=?,state=?,
          active_work_items_json=?,reason=?,sync_error=NULL WHERE id=?
        """, flow.appliedVersion() + 1, document.sourceVersion(), document.sourceVersion(), state,
        codec.write(items), reason, flow.id());
  }
  private static OaIntegrationException conflict(String code, String message) {
    return OaIntegrationException.conflict(code, message);
  }
}
