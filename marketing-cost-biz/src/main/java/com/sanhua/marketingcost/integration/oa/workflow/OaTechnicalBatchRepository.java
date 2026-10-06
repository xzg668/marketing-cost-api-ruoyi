package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 原生 I02/I03/I05 的业务批次及分步回执；与产品快照通过既有消息编号关联。 */
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
    OaWorkflowResult result,
    String returnStep,
    int returnAttempt,
    ObjectNode rejectRequest,
    OaWorkflowResult peopleResult
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
            : json.convertValue(codec.read(row.getString("result_json")), OaWorkflowResult.class),
          row.getString("return_step"),
          row.getInt("return_attempt"),
          row.getString("reject_request_json") == null ? null : (ObjectNode) codec.read(row.getString("reject_request_json")),
          row.getString("people_result_json") == null ? null
              : json.convertValue(codec.read(row.getString("people_result_json")), OaWorkflowResult.class)
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

  public void prepareReturn(String id, ObjectNode peopleRequest, ObjectNode rejectRequest) {
    requireOne(jdbc.update("""
        UPDATE lp_oa_technical_batch SET request_json=?,reject_request_json=?,return_step='PEOPLE'
        WHERE id=? AND operation='I05' AND status='PREPARED' AND return_step IS NULL
        """, codec.write(peopleRequest), codec.write(rejectRequest), id));
  }

  public boolean startReturnStep(Batch batch) {
    return jdbc.update("""
        UPDATE lp_oa_technical_batch SET status='SENDING',return_attempt=return_attempt+1,result_json=NULL,updated_at=NOW(3)
        WHERE id=? AND operation='I05' AND status=? AND return_step=? AND return_attempt=?
        """, batch.id(), batch.status(), batch.returnStep(), batch.returnAttempt()) == 1;
  }

  public void receiveReturnStep(Batch sending, OaWorkflowResult result) {
    boolean people = "PEOPLE".equals(sending.returnStep());
    boolean success = result.status() == OaWorkflowResult.Status.SUCCESS;
    String state = success ? (people ? "REJECT_READY" : "OA_ACCEPTED")
        : !people && (result.status() == OaWorkflowResult.Status.REJECTED || result.status() == OaWorkflowResult.Status.NOT_SENT)
            ? "RETURN_FAILED" : result.status().name();
    requireOne(jdbc.update("""
        UPDATE lp_oa_technical_batch SET status=?,return_step=?,result_json=?,
          people_result_json=IF(?=1,CAST(? AS JSON),people_result_json),updated_at=NOW(3)
        WHERE id=? AND operation='I05' AND status='SENDING' AND return_step=? AND return_attempt=?
        """, state, people && success ? "REJECT" : sending.returnStep(),
        people && success ? null : codec.write(result), people ? 1 : 0, codec.write(result),
        sending.id(), sending.returnStep(), sending.returnAttempt()));
  }

  /** 第一阶段的人工核实作为独立凭据保存，保留原始未知回执。 */
  public void confirmPeopleManually(Batch batch, OaWorkflowResult confirmed) {
    requireOne(jdbc.update("""
        UPDATE lp_oa_technical_batch SET status='REJECT_READY',return_step='REJECT',
          result_json=NULL,people_result_json=?,updated_at=NOW(3)
        WHERE id=? AND operation='I05' AND status='UNKNOWN' AND return_step='PEOPLE' AND return_attempt=?
        """, codec.write(confirmed), batch.id(), batch.returnAttempt()));
  }

  /** 已鉴权的技术节点通知确认第二步结果；不重新发送，也不覆盖明确失败。 */
  public void confirmReturnNotification(Batch batch, long notificationId) {
    var result = new OaWorkflowResult("OA-NOTIFY:" + notificationId, OaWorkflowResult.Status.SUCCESS,
        null, "0", "OA技术节点通知已确认原流程退回", batch.request().path("requestId").asText(), null, 0);
    requireOne(jdbc.update("""
        UPDATE lp_oa_technical_batch SET status='OA_ACCEPTED',result_json=?,updated_at=NOW(3)
        WHERE id=? AND operation='I05' AND status='UNKNOWN' AND return_step='REJECT' AND return_attempt=?
        """, codec.write(result), batch.id(), batch.returnAttempt()));
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
