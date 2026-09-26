package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 原生 I02/I03/I05 的一次发送及回执；与产品快照通过既有消息编号关联。 */
@Repository
public class OaTechnicalBatchRepository {

  public record Batch(
    String id,
    String operation,
    long formId,
    long actorId,
    String requestKey,
    String inputFingerprint,
    String status,
    ObjectNode request,
    OaWorkflowResult result
  ) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final OaMessageCodec codec;

  public OaTechnicalBatchRepository(JdbcTemplate jdbc, ObjectMapper json, OaMessageCodec codec) {
    this.jdbc = jdbc;
    this.json = json;
    this.codec = codec;
  }

  public Batch find(String id, boolean lock) {
    var rows = jdbc.query(
      "SELECT * FROM lp_oa_technical_batch WHERE id=?" + (lock ? " FOR UPDATE" : ""),
      (row, index) ->
        new Batch(
          row.getString("id"),
          row.getString("operation"),
          row.getLong("oa_form_id"),
          row.getLong("actor_user_id"),
          row.getString("request_key"),
          row.getString("input_fingerprint"),
          row.getString("status"),
          (ObjectNode) codec.read(row.getString("request_json")),
          row.getString("result_json") == null
            ? null
            : json.convertValue(codec.read(row.getString("result_json")), OaWorkflowResult.class)
        ),
      id
    );
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Batch findRequest(String operation, long actorId, String key) {
    var ids = jdbc.queryForList(
      "SELECT id FROM lp_oa_technical_batch WHERE operation=? AND actor_user_id=? AND request_key=?",
      String.class,
      operation,
      actorId,
      key
    );
    return ids.isEmpty() ? null : find(ids.getFirst(), true);
  }

  public void insert(String id, String operation, long formId, long actorId, String key, String fingerprint) {
    jdbc.update(
      """
      INSERT INTO lp_oa_technical_batch(id,operation,oa_form_id,actor_user_id,request_key,input_fingerprint,request_json)
      VALUES(?,?,?,?,?,?,'{}')
      """,
      id,
      operation,
      formId,
      actorId,
      key,
      fingerprint
    );
  }

  public void prepared(String id, ObjectNode request) {
    requireOne(
      jdbc.update(
        "UPDATE lp_oa_technical_batch SET request_json=? WHERE id=? AND status='PREPARED'",
        codec.write(request),
        id
      )
    );
  }

  public boolean startSending(String id) {
    return (
      jdbc.update(
        "UPDATE lp_oa_technical_batch SET status='SENDING',updated_at=NOW(3) WHERE id=? AND status='PREPARED'",
        id
      ) == 1
    );
  }

  public void received(String id, OaWorkflowResult result) {
    // 审批通知可能先于同步 HTTP 回执到达，已被通知确认的提交不能被迟到回执降级。
    if ("SUCCESS".equals(find(id, true).status())) return;
    requireOne(
      jdbc.update(
        """
        UPDATE lp_oa_technical_batch SET status=?,result_json=?,updated_at=NOW(3) WHERE id=? AND status='SENDING'
        """,
        result.status() == OaWorkflowResult.Status.SUCCESS ? "OA_ACCEPTED" : result.status().name(),
        codec.write(result),
        id
      )
    );
  }

  public void completed(String id) {
    requireOne(
      jdbc.update(
        "UPDATE lp_oa_technical_batch SET status='SUCCESS',updated_at=NOW(3) WHERE id=? AND status='OA_ACCEPTED'",
        id
      )
    );
  }

  /** 全部产品冻结提交已由通知确认后，解除本人的 I03 结果未确认状态。 */
  public void confirmSubmissionNotification(String id, long notificationId) {
    var batch = find(id, true);
    if (batch == null || !"I03".equals(batch.operation()) || "SUCCESS".equals(batch.status())) return;
    if (!java.util.Set.of("SENDING", "UNKNOWN", "OA_ACCEPTED").contains(batch.status())) {
      throw new IllegalStateException("本次 I03 尚未发送或已明确失败，不能用审批通知确认");
    }
    boolean pending = Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_oa_integration_message m
          LEFT JOIN lp_quote_tech_submission s ON s.outbound_message_id=m.id
          WHERE m.technical_batch_id=? AND COALESCE(s.submission_status,'') NOT IN ('SENT','APPROVED','RETURNED'))
        """, Boolean.class, id));
    if (pending) throw new IllegalStateException("通知未覆盖本人本次提交的全部产品，请核对任务关联");
    var result = new OaWorkflowResult("OA-NOTIFY:" + notificationId, OaWorkflowResult.Status.SUCCESS,
        null, "0", "OA状态通知已确认本次提交受理", batch.request().path("requestId").asText(), null, 0);
    jdbc.update("UPDATE lp_oa_technical_batch SET status='SUCCESS',result_json=?,updated_at=NOW(3) WHERE id=?",
        codec.write(result), id);
  }

  public void linkMessage(String batchId, long messageId) {
    requireOne(
      jdbc.update(
        "UPDATE lp_oa_integration_message SET technical_batch_id=? WHERE id=? AND technical_batch_id IS NULL",
        batchId,
        messageId
      )
    );
  }

  public List<Long> messageIds(String batchId) {
    return jdbc.queryForList(
      "SELECT id FROM lp_oa_integration_message WHERE technical_batch_id=? ORDER BY id",
      Long.class,
      batchId
    );
  }

  public void finishMessages(Batch batch) {
    var result = batch.result();
    String status =
      result.status() == OaWorkflowResult.Status.SUCCESS
        ? "PROCESSED"
        : result.status() == OaWorkflowResult.Status.REJECTED
          ? "REJECTED"
          : "FAILED";
    jdbc.update(
      """
      UPDATE lp_oa_integration_message SET status=?,attempt_count=1,result_json=?,error_stage='DELIVERY',
        error_code=?,error_message=?,processed_at=NOW(3) WHERE technical_batch_id=? AND status='RECEIVED'
      """,
      status,
      codec.write(result),
      result.errorCode(),
      result.message(),
      batch.id()
    );
  }

  private void requireOne(int count) {
    if (count != 1) throw new IllegalStateException("OA批次状态已变化，请刷新核实原请求");
  }
}
