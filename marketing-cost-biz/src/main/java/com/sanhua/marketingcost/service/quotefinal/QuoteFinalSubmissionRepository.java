package com.sanhua.marketingcost.service.quotefinal;

import com.sanhua.marketingcost.integration.oa.OaPeer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteFinalSubmissionRepository {
  public record Submission(long id, long formId, String oaNo, String month, int round,
      OaPeer peer, String documentId, String flowId, String approver, String businessUnit,
      String status, String step, Long messageId, int stepAttempt, String fingerprint,
      String snapshotJson, long operatorId, String operatorExternalId, long returnSequence,
      String returnReason, String error) {}
  private final JdbcTemplate jdbc;
  public QuoteFinalSubmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public void lockForm(long id) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, id);
  }
  public Submission latest(long formId, String month) {
    return one("SELECT * FROM lp_quote_final_submission WHERE oa_form_id=? AND accounting_month=? ORDER BY submission_round DESC LIMIT 1", formId, month);
  }
  public Submission lock(long id) {
    return one("SELECT * FROM lp_quote_final_submission WHERE id=? FOR UPDATE", id);
  }
  public Submission find(long id) {
    return one("SELECT * FROM lp_quote_final_submission WHERE id=?", id);
  }
  public Submission byMessage(long id) {
    return one("SELECT * FROM lp_quote_final_submission WHERE outbound_message_id=? FOR UPDATE", id);
  }
  public String document(long formId, OaPeer peer) {
    var bindings = jdbc.queryForList("SELECT source_system,environment,external_document_id FROM lp_oa_quote_document WHERE oa_form_id=?", formId);
    if (bindings.size() != 1 || !Objects.equals(bindings.getFirst().get("source_system"), peer.sourceSystem())
        || !Objects.equals(bindings.getFirst().get("environment"), peer.environment())) {
      throw new IllegalArgumentException("本报价未关联当前 OA 来源，不能提交节点");
    }
    return bindings.getFirst().get("external_document_id").toString();
  }
  public Submission create(long formId, String oaNo, String month, int round, OaPeer peer,
      String document, String businessUnit, String fingerprint, String snapshot, long operatorId, String externalId) {
    jdbc.update("""
        INSERT INTO lp_quote_final_submission(oa_form_id,oa_no,accounting_month,submission_round,
          source_system,environment,external_document_id,business_unit_type,content_fingerprint,
          cost_snapshot_json,operator_user_id,operator_external_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
        """, formId, oaNo, month, round, peer.sourceSystem(), peer.environment(), document, businessUnit,
        fingerprint, snapshot, operatorId, externalId);
    return latest(formId, month);
  }
  public void enqueue(long id, String step, long messageId) {
    jdbc.update("UPDATE lp_quote_final_submission SET current_step=?,outbound_message_id=?,step_attempt=step_attempt+1,status='PENDING',error_message=NULL WHERE id=?", step, messageId, id);
  }
  public void bindFlow(long id, String flow, String approver) {
    jdbc.update("UPDATE lp_quote_final_submission SET external_flow_id=?,approver_external_id=? WHERE id=?", flow, approver, id);
  }
  public void state(long id, String status, String error) {
    jdbc.update("UPDATE lp_quote_final_submission SET status=?,error_message=?,submitted_at=IF(?='SUBMITTED',NOW(3),submitted_at) WHERE id=?", status, error, status, id);
  }
  public void returned(long id, long sequence, String reason) {
    jdbc.update("UPDATE lp_quote_final_submission SET status='RETURNED',return_sequence=?,return_reason=?,returned_at=NOW(3) WHERE id=?", sequence, reason, id);
  }
  private Submission one(String sql, Object... args) {
    var results = jdbc.query(sql, this::read, args);
    return results.isEmpty() ? null : results.getFirst();
  }
  private Submission read(ResultSet r, int n) throws SQLException {
    String unit = r.getString("business_unit_type");
    return new Submission(r.getLong("id"), r.getLong("oa_form_id"), r.getString("oa_no"), r.getString("accounting_month"),
        r.getInt("submission_round"), new OaPeer(r.getString("source_system"), r.getString("environment"), java.util.Set.of(unit)),
        r.getString("external_document_id"), r.getString("external_flow_id"), r.getString("approver_external_id"), unit,
        r.getString("status"), r.getString("current_step"), r.getObject("outbound_message_id", Long.class), r.getInt("step_attempt"),
        r.getString("content_fingerprint"), r.getString("cost_snapshot_json"), r.getLong("operator_user_id"), r.getString("operator_external_id"),
        r.getLong("return_sequence"), r.getString("return_reason"), r.getString("error_message"));
  }
}
