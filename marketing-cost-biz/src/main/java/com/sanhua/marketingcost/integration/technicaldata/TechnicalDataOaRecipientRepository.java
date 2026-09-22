package com.sanhua.marketingcost.integration.technicaldata;

import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 一人一条办理分支。分工调整只替换受影响分支，保留其他人的审批和版本。 */
@Repository
public class TechnicalDataOaRecipientRepository {
  public record Recipient(long id, long taskId, int assignmentVersion, long userId, String name,
      String externalUserId, String action, List<String> modules, long messageId,
      String externalTaskId, String dispatchStatus, String todoStatus, String lastError,
      String departmentName, String leaderExternalId, String leaderName,
      Long latestSubmissionId, int submissionRound, long callbackSequence, String returnReason, boolean active, Long returnMessageId, Long returnRequestedBy) {}

  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;

  public TechnicalDataOaRecipientRepository(JdbcTemplate jdbc, OaMessageCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  public void insert(long taskId, int version, long userId, String name, String externalUserId,
      String action, List<String> modules, long messageId) {
    jdbc.update("""
        INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,
          external_user_id,action,module_types_json,outbound_message_id,integration_task_id)
        VALUES(?,?,?,?,?,?,?,?,?)
        """, taskId, version, userId, name, externalUserId, action, codec.write(modules), messageId, "T-" + java.util.UUID.randomUUID());
  }

  public List<Recipient> find(long taskId, int version) {
    return jdbc.query("SELECT * FROM lp_quote_tech_oa_recipient WHERE task_id=? AND assignment_version=? ORDER BY assignee_user_id",
        this::recipient, taskId, version);
  }

  public List<Recipient> current(long taskId) {
    return jdbc.query("SELECT * FROM lp_quote_tech_oa_recipient WHERE task_id=? AND active_flag=1 ORDER BY assignee_user_id",
        this::recipient, taskId);
  }

  public Recipient findById(long id) {
    return first(jdbc.query("SELECT * FROM lp_quote_tech_oa_recipient WHERE id=?", this::recipient, id));
  }

  public Recipient findByMessage(long messageId) {
    return first(jdbc.query("SELECT * FROM lp_quote_tech_oa_recipient WHERE outbound_message_id=?", this::recipient, messageId));
  }

  public void confirm(long messageId, String externalTaskId, String department, String leaderId, String leaderName) {
    jdbc.update("""
        UPDATE lp_quote_tech_oa_recipient SET external_task_id=?,department_name=?,leader_external_id=?,leader_name=?,
          dispatch_status='CONFIRMED',last_error=NULL,row_version=row_version+1,updated_at=NOW(3)
        WHERE outbound_message_id=? AND dispatch_status IN ('QUEUED','UNKNOWN')
        """, externalTaskId, department, leaderId, leaderName, messageId);
  }

  public void unconfirmed(long messageId, boolean rejected, String error) {
    jdbc.update("""
        UPDATE lp_quote_tech_oa_recipient SET dispatch_status=?,last_error=?,row_version=row_version+1,updated_at=NOW(3)
        WHERE outbound_message_id=? AND dispatch_status IN ('QUEUED','UNKNOWN')
        """, rejected ? "REJECTED" : "UNKNOWN", error, messageId);
  }

  public void assignModules(long taskId, Map<Long, List<String>> groups, Map<Long, String> names) {
    for (var group : groups.entrySet()) {
      for (String type : group.getValue()) assignModule(taskId, type, group.getKey(), names.get(group.getKey()));
    }
  }

  public void activate(long taskId, int version) {
    var batch = find(taskId, version);
    if (batch.isEmpty() || batch.stream().anyMatch(row -> !"CONFIRMED".equals(row.dispatchStatus()))) {
      throw conflict("各人待办尚未全部确认");
    }
    if (batch.stream().allMatch(row -> !"WAITING".equals(row.todoStatus()))) return;
    for (var next : batch) {
      jdbc.update("""
          UPDATE lp_quote_tech_oa_recipient SET active_flag=0,todo_status='SUPERSEDED',row_version=row_version+1,updated_at=NOW(3)
          WHERE task_id=? AND assignee_user_id=? AND active_flag=1 AND id<>?
          """, taskId, next.userId(), next.id());
      jdbc.update("""
          UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
          SET m.assignee_user_id=NULL,m.assignee_name=NULL,m.row_version=m.row_version+1,m.updated_at=NOW(3)
          WHERE p.task_id=? AND p.active_flag=1 AND m.assignee_user_id=?
          """, taskId, next.userId());
    }
    for (var next : batch) {
      boolean assign = "ASSIGN".equals(next.action());
      if (assign) for (String type : next.modules()) assignModule(taskId, type, next.userId(), next.name());
      jdbc.update("UPDATE lp_quote_tech_oa_recipient SET active_flag=?,todo_status=?,row_version=row_version+1,updated_at=NOW(3) WHERE id=?",
          assign ? 1 : 0, assign ? "OPEN" : "CANCELLED", next.id());
    }
    if (current(taskId).isEmpty()) {
      jdbc.update("UPDATE lp_quote_tech_product SET active_flag=0,active_lock_key=NULL,row_version=row_version+1,updated_at=NOW(3) WHERE task_id=? AND active_flag=1", taskId);
      jdbc.update("UPDATE lp_quote_tech_task SET active_flag=0,active_lock_key=NULL,task_status='CANCELLED',external_task_status='CANCELLED',task_version=task_version+1,updated_at=NOW(3) WHERE id=?", taskId);
    } else {
      jdbc.update("""
          UPDATE lp_quote_tech_task SET external_task_id=NULL,external_task_status='PUBLISHED',external_last_error=NULL,
            external_last_sync_at=NOW(3),task_version=task_version+1,updated_at=NOW(3) WHERE id=?
          """, taskId);
    }
  }

  public void prepared(long id, long submissionId, int round) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_oa_recipient SET todo_status='PREPARED',latest_submission_id=?,submission_round=?,
          return_reason=NULL,row_version=row_version+1,updated_at=NOW(3) WHERE id=? AND active_flag=1 AND todo_status='OPEN'
        """, submissionId, round, id) != 1) throw conflict("本人待办已变化，不能重复提交");
  }

  public void state(long id, long submissionId, String expected, String target, String reason) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_oa_recipient SET todo_status=?,return_reason=?,row_version=row_version+1,updated_at=NOW(3)
        WHERE id=? AND active_flag=1 AND latest_submission_id=? AND todo_status=?
        """, target, reason, id, submissionId, expected) != 1) throw conflict("本人办理状态已变化");
  }

  public void requestReturn(long id, long submissionId, long messageId, long userId, String reason) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_oa_recipient SET todo_status='RETURN_PENDING',return_message_id=?,return_requested_by=?,
          return_reason=?,row_version=row_version+1,updated_at=NOW(3)
        WHERE id=? AND active_flag=1 AND latest_submission_id=? AND todo_status='DONE'
        """, messageId, userId, reason, id, submissionId) != 1) throw conflict("所选人员的审批版本已变化");
  }

  public void returnedTodo(long id, String externalTaskId) {
    jdbc.update("UPDATE lp_quote_tech_oa_recipient SET external_task_id=?,updated_at=NOW(3) WHERE id=? AND active_flag=1",
        externalTaskId, id);
  }

  public void sequence(long id, long sequence) {
    if (jdbc.update("UPDATE lp_quote_tech_oa_recipient SET callback_sequence=?,updated_at=NOW(3) WHERE id=? AND callback_sequence<?",
        sequence, id, sequence) != 1) throw conflict("审批事件顺序已过期");
  }

  /** 产品状态仅作汇总，不控制各人编辑权限。 */
  public void refreshTask(long taskId) {
    var current = current(taskId);
    boolean approved = !current.isEmpty() && current.stream().allMatch(row -> "DONE".equals(row.todoStatus()));
    boolean open = current.stream().anyMatch(row -> "OPEN".equals(row.todoStatus()));
    boolean prepared = current.stream().anyMatch(row -> "PREPARED".equals(row.todoStatus()));
    String status = approved ? "APPROVED" : open ? "IN_PROGRESS" : prepared ? "PREPARED" : "SUBMITTED";
    jdbc.update("""
        UPDATE lp_quote_tech_task SET task_status=?,review_status=?,task_version=task_version+1,updated_at=NOW(3) WHERE id=? AND active_flag=1
        """, status, approved ? "PASSED" : open ? "NOT_STARTED" : "PENDING", taskId);
  }

  private void assignModule(long taskId, String type, long userId, String name) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
        SET m.assignee_user_id=?,m.assignee_name=?,m.row_version=m.row_version+1,m.updated_at=NOW(3)
        WHERE p.task_id=? AND p.active_flag=1 AND m.required_flag=1 AND m.module_type=?
        """, userId, name, taskId, type) != 1) throw conflict("分派期间产品模块范围已变化");
  }

  private Recipient first(List<Recipient> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
  private OaIntegrationException conflict(String message) {
    return OaIntegrationException.conflict("OA_PERSON_STATE_CONFLICT", message);
  }

  private Recipient recipient(ResultSet row, int index) throws SQLException {
    List<String> modules = new ArrayList<>();
    codec.read(row.getString("module_types_json")).forEach(item -> modules.add(item.asText()));
    return new Recipient(row.getLong("id"), row.getLong("task_id"), row.getInt("assignment_version"),
        row.getLong("assignee_user_id"), row.getString("assignee_name"), row.getString("external_user_id"),
        row.getString("action"), List.copyOf(modules), row.getLong("outbound_message_id"), row.getString("external_task_id"),
        row.getString("dispatch_status"), row.getString("todo_status"), row.getString("last_error"),
        row.getString("department_name"), row.getString("leader_external_id"), row.getString("leader_name"),
        row.getObject("latest_submission_id", Long.class), row.getInt("submission_round"), row.getLong("callback_sequence"),
        row.getString("return_reason"), row.getBoolean("active_flag"),
        row.getObject("return_message_id", Long.class), row.getObject("return_requested_by", Long.class));
  }
}
