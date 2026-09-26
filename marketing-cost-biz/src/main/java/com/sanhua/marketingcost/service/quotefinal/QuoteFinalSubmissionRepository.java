package com.sanhua.marketingcost.service.quotefinal;

import com.sanhua.marketingcost.integration.oa.OaPeer;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 成本提交快照与 OA 报文共用既有业务表、消息表；不再保存模拟 OA 的分步待办。 */
@Repository
public class QuoteFinalSubmissionRepository {
  public record Submission(long id, long formId, String oaNo, String month, int round,
      OaPeer peer, String documentId, String businessUnit, String status, Long messageId,
      String fingerprint, String snapshotJson, long operatorId, String operatorExternalId,
      long returnSequence, String returnReason, String error) {}
  private final JdbcTemplate jdbc;

  public QuoteFinalSubmissionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public void lockForm(long id) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, id);
  }
  public Submission latest(long formId) {
    return one("SELECT * FROM lp_quote_final_submission WHERE oa_form_id=? ORDER BY id DESC LIMIT 1", formId);
  }
  public Submission returnedBaseline(long formId) {
    return one("SELECT * FROM lp_quote_final_submission WHERE oa_form_id=? AND status='RETURNED' ORDER BY id DESC LIMIT 1", formId);
  }
  public Submission lock(long id) {
    return one("SELECT * FROM lp_quote_final_submission WHERE id=? FOR UPDATE", id);
  }
  public Submission find(long id) {
    return one("SELECT * FROM lp_quote_final_submission WHERE id=?", id);
  }
  public Submission byRequest(String requestId) {
    return one("""
        SELECT s.* FROM lp_quote_final_submission s JOIN lp_oa_integration_message m ON m.id=s.outbound_message_id
        WHERE m.direction='OUTBOUND' AND m.interface_type='QUOTE_COST_SUBMIT' AND m.request_id=?
        """, requestId);
  }
  public Map<Long, String> sourceRows(long formId) {
    return jdbc.queryForList("SELECT oa_form_item_id,field_value FROM lp_oa_form_item_extra_field WHERE oa_form_id=? AND field_code='OA_ROW_ID'", formId)
        .stream().collect(Collectors.toMap(r -> ((Number)r.get("oa_form_item_id")).longValue(), r -> r.get("field_value").toString()));
  }
  public long sourceVersion(long formId) {
    return jdbc.queryForObject("SELECT source_version FROM lp_oa_quote_document WHERE oa_form_id=?", Long.class, formId);
  }
  public boolean linked(long formId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_quote_document WHERE oa_form_id=?", Integer.class, formId) == 1;
  }
  public Submission create(long formId, String oaNo, String month, OaPeer peer, String document,
      String businessUnit, String fingerprint, String snapshot, long operatorId, String employeeNo,
      long messageId) {
    jdbc.update("""
        INSERT INTO lp_quote_final_submission(oa_form_id,oa_no,accounting_month,submission_round,
          source_system,environment,external_document_id,business_unit_type,content_fingerprint,
          cost_snapshot_json,operator_user_id,operator_external_id,outbound_message_id,source_form_version)
        SELECT ?,?,?,COALESCE(MAX(submission_round),0)+1,?,?,?,?,?,?,?,?,?,?
        FROM lp_quote_final_submission WHERE oa_form_id=? AND accounting_month=?
        """, formId, oaNo, month, peer.sourceSystem(), peer.environment(), document, businessUnit,
        fingerprint, snapshot, operatorId, employeeNo, messageId, sourceVersion(formId), formId, month);
    jdbc.update("UPDATE lp_oa_integration_message SET status='PROCESSING',attempt_count=1 WHERE id=? AND status='RECEIVED'", messageId);
    return latest(formId);
  }
  public void received(Submission submission, OaWorkflowResult result, String resultJson) {
    // I08 可能先到达，迟到的同步回执仅补日志，不能覆盖 RETURNED / COMPLETED。
    String next = switch (result.status()) {
      case SUCCESS -> "SUBMITTED";
      case REJECTED -> "REJECTED";
      case NOT_SENT -> "NOT_SENT";
      case UNKNOWN -> "UNKNOWN";
    };
    jdbc.update("""
        UPDATE lp_quote_final_submission SET status=?,error_message=?,submitted_at=IF(?='SUBMITTED',NOW(3),submitted_at)
        WHERE id=? AND status IN ('PENDING','UNKNOWN')
        """, next, result.status() == OaWorkflowResult.Status.SUCCESS ? null : result.message(), next, submission.id());
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status=?,result_json=?,error_code=?,error_message=?,processed_at=NOW(3)
        WHERE id=? AND status<>'PROCESSED'
        """, switch (result.status()) {
          case SUCCESS -> "PROCESSED";
          case UNKNOWN -> "WAITING_HANDLER";
          case NOT_SENT, REJECTED -> "REJECTED";
        },
        resultJson, result.errorCode(), result.message(), submission.messageId());
  }
  public void state(long id, String status, String error) {
    jdbc.update("UPDATE lp_quote_final_submission SET status=?,error_message=?,submitted_at=IF(?='SUBMITTED',NOW(3),submitted_at) WHERE id=?", status, error, status, id);
  }
  public void returned(long id, long sequence, String reason) {
    jdbc.update("UPDATE lp_quote_final_submission SET status='RETURNED',error_message=NULL,return_sequence=?,return_reason=?,returned_at=NOW(3) WHERE id=?", sequence, reason, id);
  }
  private Submission one(String sql, Object... args) {
    var results = jdbc.query(sql, this::read, args);
    return results.isEmpty() ? null : results.getFirst();
  }
  private Submission read(ResultSet r, int n) throws SQLException {
    String unit = r.getString("business_unit_type");
    return new Submission(r.getLong("id"), r.getLong("oa_form_id"), r.getString("oa_no"), r.getString("accounting_month"),
        r.getInt("submission_round"), new OaPeer(r.getString("source_system"), r.getString("environment"), Set.of(unit)),
        r.getString("external_document_id"), unit, r.getString("status"), r.getObject("outbound_message_id", Long.class),
        r.getString("content_fingerprint"), r.getString("cost_snapshot_json"), r.getLong("operator_user_id"), r.getString("operator_external_id"),
        r.getLong("return_sequence"), r.getString("return_reason"), r.getString("error_message"));
  }
}
