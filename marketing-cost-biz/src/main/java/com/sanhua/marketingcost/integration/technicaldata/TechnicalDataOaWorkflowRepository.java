package com.sanhua.marketingcost.integration.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaPeer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只保存业务身份与流程进度；发送原文及每次处理记录在共用接口消息表中。 */
@Repository
public class TechnicalDataOaWorkflowRepository {
  public record Flow(long id, String sourceSystem, String environment, long oaFormId,
      String accountingMonth, String documentId, String externalFlowId, long financeSequence,
      boolean financeReady, Long financeUserId, String confirmedFingerprint) {}

  private final JdbcTemplate jdbc;
  public TechnicalDataOaWorkflowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Flow bindFlow(QuoteTechTask task, OaPeer peer) {
    var bindings = jdbc.queryForList("SELECT source_system,environment,external_document_id FROM lp_oa_quote_document WHERE oa_form_id=?", task.getOaFormId());
    // 手工/PDF 导入的需求也可在 MOCK 中办理，使用明确的本地业务身份，真实 OA 适配不得猜编号。
    String documentId = "QUOTE-" + task.getOaFormId();
    if (!bindings.isEmpty()) {
      var binding = bindings.getFirst();
      if (!peer.sourceSystem().equals(binding.get("source_system")) || !peer.environment().equals(binding.get("environment"))) {
        throw OaIntegrationException.conflict("OA_DOCUMENT_PEER_CONFLICT", "需求来源与当前 OA 审批通道不一致");
      }
      documentId = binding.get("external_document_id").toString();
    }
    jdbc.update("""
        INSERT INTO lp_oa_technical_flow(source_system,environment,oa_form_id,accounting_month,external_document_id)
        VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), task.getOaFormId(), task.getAccountingMonth(), documentId);
    var flow = jdbc.query("SELECT * FROM lp_oa_technical_flow WHERE oa_form_id=? AND accounting_month=? FOR UPDATE",
        this::flow, task.getOaFormId(), task.getAccountingMonth()).getFirst();
    if (!flow.sourceSystem().equals(peer.sourceSystem()) || !flow.environment().equals(peer.environment())
        || !flow.documentId().equals(documentId)) throw OaIntegrationException.conflict("OA_FLOW_SCOPE_CONFLICT", "该产品月份已关联其他 OA 来源");
    return flow;
  }

  public Flow lockFlow(long id) {
    var rows = jdbc.query("SELECT * FROM lp_oa_technical_flow WHERE id=? FOR UPDATE", this::flow, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Flow findFlow(long id) {
    var rows = jdbc.query("SELECT * FROM lp_oa_technical_flow WHERE id=?", this::flow, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void bindDispatch(QuoteTechTask task, Flow flow, int assignmentVersion, long messageId) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_task SET oa_flow_id=?,oa_environment=?,oa_assignment_version=?,oa_dispatch_message_id=?,
          external_system=?,external_task_status='SYNC_PENDING',external_last_error=NULL,
          task_version=task_version+1,updated_at=NOW(3)
        WHERE id=? AND task_version=? AND active_flag=1
        """, flow.id(), flow.environment(), assignmentVersion, messageId, flow.sourceSystem(), task.getId(), task.getTaskVersion()) != 1) {
      throw OaIntegrationException.conflict("OA_DISPATCH_CONFLICT", "分派时任务版本已变化");
    }
    invalidateFinance(flow.id());
  }

  public void bindExternalFlow(QuoteTechTask task, String externalFlowId) {
    var flow = lockFlow(task.getOaFlowId());
    if (flow.externalFlowId() != null && !Objects.equals(flow.externalFlowId(), externalFlowId)) {
      throw OaIntegrationException.conflict("OA_FLOW_ID_CONFLICT", "同一单据月份收到不同 OA 流程身份");
    }
    jdbc.update("UPDATE lp_oa_technical_flow SET external_flow_id=?,updated_at=NOW(3) WHERE id=?", externalFlowId, flow.id());

  }

  public void dispatchUnconfirmed(long taskId, boolean rejected, String error) {
    jdbc.update("""
        UPDATE lp_quote_tech_task SET external_task_status=?,external_last_error=?,
          task_status=CASE WHEN ? THEN 'UNASSIGNED' ELSE task_status END,
          assignee_user_id=CASE WHEN ? THEN NULL ELSE assignee_user_id END,
          assignee_name=CASE WHEN ? THEN NULL ELSE assignee_name END,
          external_retry_count=external_retry_count+1,external_last_sync_at=NOW(3),task_version=task_version+1,updated_at=NOW(3)
        WHERE id=?
        """, rejected ? "SYNC_FAILED" : "UNKNOWN", error, rejected, rejected, rejected, taskId);
  }

  public void invalidateFinance(Long flowId) {
    if (flowId == null) return;
    jdbc.update("""
        UPDATE lp_oa_technical_flow SET finance_ready=0,finance_confirmed_fingerprint=NULL,
          finance_confirmed_by=NULL,finance_confirmed_at=NULL,updated_at=NOW(3) WHERE id=?
        """, flowId);
  }

  public void linkSubmission(long submissionId, long messageId) {
    if (jdbc.update("UPDATE lp_quote_tech_submission SET outbound_message_id=?,row_version=row_version+1 WHERE id=? AND outbound_message_id IS NULL AND submission_status='PREPARED'",
        messageId, submissionId) != 1) throw OaIntegrationException.conflict("OA_SUBMISSION_MESSAGE_CONFLICT", "提交已绑定发送报文");
  }

  public void markSending(long submissionId) {
    jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='SENDING',row_version=row_version+1 WHERE id=? AND submission_status='PREPARED'", submissionId);
  }

  public void markUnknown(long submissionId) {
    jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='UNKNOWN',row_version=row_version+1 WHERE id=? AND submission_status='SENDING'", submissionId);
  }

  public void acceptSubmission(long submissionId, String externalFlowId) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_submission SET submission_status='SENT',sent_at=NOW(3),external_flow_id=?,
          external_submission_id=CAST(id AS CHAR),row_version=row_version+1
        WHERE id=? AND submission_status IN ('SENDING','UNKNOWN')
        """, externalFlowId, submissionId) != 1) throw OaIntegrationException.conflict("OA_SUBMISSION_STATE_CONFLICT", "提交状态已变化");
  }

  public void rejectSubmission(long submissionId) {
    if (jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='FAILED',row_version=row_version+1 WHERE id=? AND submission_status IN ('SENDING','UNKNOWN')", submissionId) != 1) {
      throw OaIntegrationException.conflict("OA_SUBMISSION_STATE_CONFLICT", "提交状态已变化");
    }
  }

  public void decision(long submissionId, String decision, long messageId) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_submission SET submission_status=?,decision_message_id=?,decided_at=NOW(3),row_version=row_version+1
        WHERE id=? AND submission_status='SENT'
        """, decision, messageId, submissionId) != 1) throw OaIntegrationException.conflict("OA_DECISION_CONFLICT", "提交已存在审批结论");
  }

  public void acceptSequence(long taskId, long sequence, String eventId) {
    jdbc.update("UPDATE lp_quote_tech_task SET external_callback_seq=GREATEST(COALESCE(external_callback_seq,0),?),external_last_event_id=?,updated_at=NOW(3) WHERE id=?", sequence, eventId, taskId);
  }

  public void finance(Flow flow, long sequence, long messageId, long userId) {
    jdbc.update("UPDATE lp_oa_technical_flow SET finance_sequence=?,finance_message_id=?,finance_user_id=?,updated_at=NOW(3) WHERE id=? AND finance_sequence<?", sequence, messageId, userId, flow.id(), sequence);
  }

  public boolean refreshFinance(long flowId) {
    var counts = jdbc.queryForMap("""
        SELECT COUNT(*) task_count,COALESCE(SUM(t.task_status<>'APPROVED' OR t.external_task_status<>'PUBLISHED'
          OR p.effective_version_id IS NULL),0) pending_count,COALESCE(MAX(t.external_callback_seq),0) last_approval
        FROM lp_quote_tech_task t JOIN lp_quote_tech_product p ON p.task_id=t.id AND p.active_flag=1
        WHERE t.oa_flow_id=? AND t.active_flag=1
        """, flowId);
    var flow = lockFlow(flowId);
    boolean ready = ((Number) counts.get("task_count")).longValue() > 0
        && ((Number) counts.get("pending_count")).longValue() == 0
        // 最后一位技术审批通过与进入报价员资料节点可以在同一条 OA 通知中到达。
        && flow.financeSequence() > 0
        && flow.financeSequence() >= ((Number) counts.get("last_approval")).longValue();
    jdbc.update("UPDATE lp_oa_technical_flow SET finance_ready=?,updated_at=NOW(3) WHERE id=?", ready ? 1 : 0, flowId);
    return ready;
  }

  public java.util.List<String> businessUnits(long flowId) {
    return jdbc.queryForList("SELECT DISTINCT business_unit_type FROM lp_quote_tech_task WHERE oa_flow_id=?", String.class, flowId);
  }

  /** 按批准版本绑定报价员资料确认，任何人员重提或分工变化都使旧确认失效。 */
  public String approvalBasis(long flowId) {
    return jdbc.query("""
        SELECT r.id,s.id submission_id,s.technical_version_id,s.content_fingerprint
        FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id AND r.active_flag=1
        JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
        WHERE t.oa_flow_id=? AND t.active_flag=1 AND r.todo_status='DONE' AND s.submission_status='APPROVED'
        ORDER BY t.id,r.id
        """, (rs, index) -> rs.getLong("id") + ":" + rs.getLong("submission_id") + ":"
            + rs.getLong("technical_version_id") + ":" + rs.getString("content_fingerprint"), flowId).toString();
  }

  public void confirmFinance(long flowId, String fingerprint, long userId) {
    if (jdbc.update("""
        UPDATE lp_oa_technical_flow SET finance_confirmed_fingerprint=?,finance_confirmed_by=?,finance_confirmed_at=NOW(3),updated_at=NOW(3)
        WHERE id=? AND finance_ready=1
        """, fingerprint, userId, flowId) != 1) throw OaIntegrationException.conflict("FINANCE_NOT_READY", "OA 资料尚未具备报价员确认条件");
  }

  private Flow flow(ResultSet row, int index) throws SQLException {
    return new Flow(row.getLong("id"), row.getString("source_system"), row.getString("environment"),
        row.getLong("oa_form_id"), row.getString("accounting_month"), row.getString("external_document_id"),
        row.getString("external_flow_id"), row.getLong("finance_sequence"), row.getBoolean("finance_ready"), row.getObject("finance_user_id", Long.class),
        row.getString("finance_confirmed_fingerprint"));
  }
}
