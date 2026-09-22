package com.sanhua.marketingcost.integration.oa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 所有 claim/finish 均由应用层事务包围；业务写入和成功回执使用同一个数据库事务。 */
@Repository
public class OaMessageRepository {
  public record Message(long id, OaPeer peer, String requestId, String interfaceType,
      int schemaVersion, String rawPayload, String payloadHash, String status, int attemptCount,
      String leaseToken, String errorStage, String errorCode, String errorMessage,
      String resultJson, LocalDateTime receivedAt, LocalDateTime processedAt) {}

  private final JdbcTemplate jdbc;

  public OaMessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Message receive(OaPeer peer, OaMessageCodec.InterfaceType type, OaMessageCodec.Envelope envelope) {
    return save(peer, type, envelope, "INBOUND");
  }

  public Message enqueue(OaPeer peer, OaMessageCodec.InterfaceType type, OaMessageCodec.Envelope envelope) {
    return save(peer, type, envelope, "OUTBOUND");
  }

  private Message save(OaPeer peer, OaMessageCodec.InterfaceType type,
      OaMessageCodec.Envelope envelope, String direction) {
    jdbc.update("""
        INSERT INTO lp_oa_integration_message
          (source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,
           raw_payload,payload_hash,allowed_business_units)
        VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), direction, envelope.requestId(), type.name(),
        envelope.schemaVersion(), envelope.occurredAt(), envelope.rawJson(), envelope.hash(),
        String.join(",", peer.businessUnits().stream().sorted().toList()));
    Message message = find(peer, envelope.requestId(), direction);
    if (!envelope.hash().equals(message.payloadHash())) {
      throw OaIntegrationException.conflict("REQUEST_CONTENT_CONFLICT", "同一来源及环境的 requestId 已用于另一份报文");
    }
    return message;
  }

  public void completeQuotation(long id, String resultJson) {
    if (jdbc.update("""
        UPDATE lp_oa_integration_message SET status='PROCESSED',result_json=?,attempt_count=1,
          processed_at=NOW(3) WHERE id=? AND status='RECEIVED' AND schema_version=3
        """, resultJson, id) != 1) {
      throw new IllegalStateException("I01 request is not available for completion");
    }
    jdbc.update("""
        INSERT INTO lp_oa_integration_attempt(message_id,attempt_no,mapping_version,stage,status,finished_at)
        VALUES(?,1,'i01-20260917','BUSINESS','PROCESSED',NOW(3))
        """, id);
  }

  public Message find(OaPeer peer, String requestId) {
    return find(peer, requestId, "INBOUND");
  }

  private Message find(OaPeer peer, String requestId, String direction) {
    List<Message> found = jdbc.query("""
        SELECT * FROM lp_oa_integration_message
        WHERE source_system=? AND environment=? AND direction=? AND request_id=?
        """, this::read, peer.sourceSystem(), peer.environment(), direction, requestId);
    return found.isEmpty() ? null : found.getFirst();
  }

  public Message claim(String environment, int leaseSeconds, int maxAttempts) {
    return claim(environment, leaseSeconds, maxAttempts, "INBOUND", null);
  }

  public Message claimOutgoing(OaPeer peer, int leaseSeconds, int maxAttempts) {
    return claim(peer.environment(), leaseSeconds, maxAttempts, "OUTBOUND", peer.sourceSystem());
  }

  private Message claim(String environment, int leaseSeconds, int maxAttempts,
      String direction, String sourceSystem) {
    List<Message> found = jdbc.query("""
        SELECT * FROM lp_oa_integration_message WHERE environment=? AND direction=?
          AND schema_version<>4
          AND (? IS NULL OR source_system=?)
          AND ((status='RECEIVED' AND next_attempt_at<=NOW(3))
            OR (status='PROCESSING' AND lease_until<=NOW(3)))
        ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
        """, this::read, environment, direction, sourceSystem, sourceSystem);
    if (found.isEmpty()) return null;
    Message message = found.getFirst();
    if ("PROCESSING".equals(message.status())) {
      completeAttempt(message, "RECOVERY", "INTERRUPTED", "LEASE_EXPIRED", "上次处理未完成，租约到期后恢复");
    }
    if (message.attemptCount() >= maxAttempts
        && ("INBOUND".equals(direction) || "PROCESSING".equals(message.status()))) {
      jdbc.update("""
          UPDATE lp_oa_integration_message SET status='FAILED',lease_token=NULL,lease_until=NULL,
            error_stage='RECOVERY',error_code='ATTEMPTS_EXHAUSTED',error_message='恢复次数已达上限，请按请求编号排查',
            processed_at=NOW(3) WHERE id=?
          """, message.id());
      return null;
    }
    String token = UUID.randomUUID().toString();
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='PROCESSING',attempt_count=attempt_count+1,
          lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(3)),error_stage=NULL,error_code=NULL,error_message=NULL
        WHERE id=?
        """, token, leaseSeconds, message.id());
    jdbc.update("""
        INSERT INTO lp_oa_integration_attempt(message_id,attempt_no,mapping_version,stage,status)
        VALUES(?,?,?,'MAPPING','RUNNING')
        """, message.id(), message.attemptCount() + 1, "oa-schema-" + message.schemaVersion());
    return lockOwned(message.id(), token);
  }

  public Message findById(long id) {
    var rows = jdbc.query("SELECT * FROM lp_oa_integration_message WHERE id=?", this::read, id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void retryOutgoing(long id) {
    // 手工核实后只追加一次发送机会，已完成 attempt 和原文保持不变。
    if (jdbc.update("""
        UPDATE lp_oa_integration_message SET status='RECEIVED',next_attempt_at=NOW(3),processed_at=NULL,
          error_stage=NULL,error_code=NULL,error_message=NULL
        WHERE id=? AND direction='OUTBOUND' AND status='FAILED'
        """, id) != 1) {
      throw OaIntegrationException.conflict("MESSAGE_NOT_RETRYABLE", "消息不在可重试状态");
    }
  }

  public void resumeQuoteEvents(OaPeer peer, long submissionId) {
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='RECEIVED',next_attempt_at=NOW(3),error_stage=NULL,
          error_code=NULL,error_message=NULL
        WHERE source_system=? AND environment=? AND direction='INBOUND' AND status='WAITING_HANDLER'
          AND COALESCE(JSON_EXTRACT(raw_payload,'$.payload.finalSubmissionId'),
              JSON_EXTRACT(raw_payload,'$.payload.event.finalSubmissionId'))=?
        """, peer.sourceSystem(), peer.environment(), submissionId);
  }

  public void resumeWorkflowEvents(OaPeer peer, long taskId) {
    jdbc.update("""
        UPDATE lp_oa_integration_message SET status='RECEIVED',next_attempt_at=NOW(3),error_stage=NULL,
          error_code=NULL,error_message=NULL
        WHERE source_system=? AND environment=? AND direction='INBOUND' AND status='WAITING_HANDLER'
          AND error_code='WAITING_SUBMISSION'
          AND COALESCE(JSON_EXTRACT(raw_payload,'$.payload.taskId'),JSON_EXTRACT(raw_payload,'$.payload.event.taskId'))=?
        """, peer.sourceSystem(), peer.environment(), taskId);
  }

  public Message lockOwned(long id, String leaseToken) {
    List<Message> found = jdbc.query("""
        SELECT * FROM lp_oa_integration_message WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE
        """, this::read, id, leaseToken);
    return found.isEmpty() ? null : found.getFirst();
  }

  public void finish(Message message, String status, String resultJson, String stage,
      String errorCode, String errorMessage, int retryDelaySeconds) {
    int changed = jdbc.update("""
        UPDATE lp_oa_integration_message SET status=?,result_json=?,error_stage=?,error_code=?,error_message=?,
          lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(3)),
          processed_at=IF(? IN ('PROCESSED','REJECTED','FAILED'),NOW(3),NULL)
        WHERE id=? AND status='PROCESSING' AND lease_token=?
        """, status, resultJson, errorCode == null ? null : stage, errorCode, errorMessage,
        retryDelaySeconds, status, message.id(), message.leaseToken());
    if (changed != 1) throw new IllegalStateException("OA processing lease lost");
    completeAttempt(message, stage, "RECEIVED".equals(status) ? "FAILED" : status, errorCode, errorMessage);
  }

  private void completeAttempt(Message message, String stage, String status, String code, String error) {
    jdbc.update("""
        UPDATE lp_oa_integration_attempt SET stage=?,status=?,finished_at=NOW(3),
          duration_ms=TIMESTAMPDIFF(MICROSECOND,started_at,NOW(3)) DIV 1000,error_code=?,error_message=?
        WHERE message_id=? AND attempt_no=? AND finished_at IS NULL
        """, stage, status, code, error, message.id(), message.attemptCount());
  }

  private Message read(ResultSet row, int index) throws SQLException {
    return new Message(row.getLong("id"), new OaPeer(row.getString("source_system"),
        row.getString("environment"), Set.copyOf(Arrays.asList(row.getString("allowed_business_units").split(",")))),
        row.getString("request_id"), row.getString("interface_type"), row.getInt("schema_version"),
        row.getString("raw_payload"), row.getString("payload_hash"), row.getString("status"),
        row.getInt("attempt_count"), row.getString("lease_token"), row.getString("error_stage"),
        row.getString("error_code"), row.getString("error_message"), row.getString("result_json"),
        row.getObject("received_at", LocalDateTime.class), row.getObject("processed_at", LocalDateTime.class));
  }
}
