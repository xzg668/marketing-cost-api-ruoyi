package com.sanhua.marketingcost.integration.oa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
    try (var call = OaInterfaceLog.start("OA_MESSAGE_STORE")) {
      call.field("direction", direction).field("interfaceType", type).field("requestId", envelope.requestId())
          .field("sourceSystem", peer.sourceSystem()).field("environment", peer.environment());
      try {
        Message message = saveMessage(peer, type, envelope, direction);
        call.field("messageId", message.id()).field("state", message.status()).field("stage", "PENDING_COMMIT");
        call.result("HANDLED", null, null);
        return message;
      } catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  private Message saveMessage(OaPeer peer, OaMessageCodec.InterfaceType type,
      OaMessageCodec.Envelope envelope, String direction) {
    jdbc.update("""
        INSERT INTO lp_oa_integration_message
          (source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,
           raw_payload,payload_hash,allowed_business_units)
        VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), direction, envelope.requestId(), type.name(),
        envelope.schemaVersion(), envelope.occurredAt(), envelope.rawJson(), envelope.hash(),
        String.join(",", peer.businessUnits().stream().sorted().toList()));
    // 唯一键冲突等待另一事务后，用当前读取得其已提交记录，避免 RR 快照看不到重复报文。
    Message message = jdbc.queryForObject("""
        SELECT * FROM lp_oa_integration_message
        WHERE source_system=? AND environment=? AND direction=? AND request_id=? FOR UPDATE
        """, this::read, peer.sourceSystem(), peer.environment(), direction, envelope.requestId());
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

  public Message findById(long id) {
    var rows = jdbc.query("SELECT * FROM lp_oa_integration_message WHERE id=?", this::read, id);
    return rows.isEmpty() ? null : rows.getFirst();
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
