package com.sanhua.marketingcost.integration.oa;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OaWorkflowNotificationRepository {
  public record Flow(long id, long appliedVersion, String state) {}
  public record Document(long id, long sourceVersion, String businessUnit) {}

  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;

  public OaWorkflowNotificationRepository(JdbcTemplate jdbc, OaMessageCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  public void lockForm(long formId) {
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, formId);
  }

  public Flow lock(OaPeer peer, String workflowRequestId) {
    jdbc.update("""
        INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,active_work_items_json)
        VALUES(?,?,?,JSON_ARRAY()) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), workflowRequestId);
    return jdbc.queryForObject("""
        SELECT id,applied_version,state FROM lp_oa_workflow_state
        WHERE source_system=? AND environment=? AND workflow_request_id=? FOR UPDATE
        """, (r, n) -> new Flow(r.getLong("id"), r.getLong("applied_version"), r.getString("state")),
        peer.sourceSystem(), peer.environment(), workflowRequestId);
  }

  public Document document(OaPeer peer, String workflowRequestId) {
    var rows = jdbc.query("""
        SELECT f.id,d.source_version,f.business_unit_type
        FROM lp_oa_quote_document d JOIN oa_form f ON f.id=d.oa_form_id AND f.deleted=0
        WHERE d.source_system=? AND d.environment=? AND d.external_document_id=?
        """, (r, n) -> new Document(r.getLong("id"), r.getLong("source_version"), r.getString("business_unit_type")),
        peer.sourceSystem(), peer.environment(), workflowRequestId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public long associate(Flow flow, OaWorkflowNotification event, long messageId, String semanticHash) {
    jdbc.update("""
        INSERT INTO lp_oa_workflow_notification(flow_id,sequence_no,message_id,semantic_hash)
        VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE flow_id=flow_id
        """, flow.id(), event.version(), messageId, semanticHash);
    var row = jdbc.queryForMap("""
        SELECT message_id,semantic_hash FROM lp_oa_workflow_notification WHERE flow_id=? AND sequence_no=?
        """, flow.id(), event.version());
    if (!semanticHash.equals(row.get("semantic_hash"))) {
      throw OaIntegrationException.conflict("SEQUENCE_CONFLICT", "同一流程通知序号内容不同：" + event.version());
    }
    return ((Number) row.get("message_id")).longValue();
  }

  public void complete(long messageId, String reply) {
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='PROCESSED',result_json=?,error_code=NULL,
          error_message=NULL,processed_at=NOW(3) WHERE id=?
        """, reply, messageId);
  }

  public void applied(Flow flow, OaWorkflowNotification event) {
    jdbc.update("""
        UPDATE lp_oa_workflow_state SET applied_version=?,form_version=?,observed_form_version=?,state=?,
          active_work_items_json=?,reason=?,sync_error=NULL WHERE id=?
        """, event.version(), event.formVersion(), event.formVersion(), event.state(),
        codec.write(event.activeWorkItems()), event.reason(), flow.id());
  }
}
