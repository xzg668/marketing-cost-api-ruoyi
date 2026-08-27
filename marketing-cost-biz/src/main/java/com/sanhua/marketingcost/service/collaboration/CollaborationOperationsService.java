package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationResult;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.Issue;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.PublicationFailure;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.PublicationFailures;
import com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.Reconciliation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** QCBP-25：只自动执行明确安全的补偿；其余异常只报告，禁止猜测修数。 */
@Service
public class CollaborationOperationsService {
  private static final Set<String> FORMAL_TABLES = Set.of(
      "lp_price_fixed_item", "lp_price_linked_item", "lp_price_range_item");
  private final JdbcTemplate jdbc;
  private final CollaborationCurrentActorProvider actorProvider;

  public CollaborationOperationsService(
      JdbcTemplate jdbc, CollaborationCurrentActorProvider actorProvider) {
    this.jdbc = jdbc;
    this.actorProvider = actorProvider;
  }

  @Transactional(readOnly = true)
  public Reconciliation reconcile() {
    List<Issue> issues = new ArrayList<>();
    addRows(issues, "DUPLICATE_ACTIVE_LOCK", "ERROR", """
        SELECT MIN(id) target_id,MIN(product_task_no) task_no,NULL oa_no,NULL item_id,
               CONCAT('活动锁 ',active_lock_key,' 同时存在 ',COUNT(*),' 个产品任务') message
        FROM lp_quote_collaboration_product_task WHERE active_flag=1
        GROUP BY active_lock_key HAVING COUNT(*)>1
        """);
    addRows(issues, "OPEN_GAP_COUNT_MISMATCH", "ERROR", """
        SELECT p.id target_id,p.product_task_no task_no,NULL oa_no,NULL item_id,
               CONCAT('产品记录开放缺口=',p.open_gap_count,'，实际=',COUNT(g.id)) message
        FROM lp_quote_collaboration_product_task p
        LEFT JOIN lp_quote_collaboration_gap g ON g.product_task_id=p.id
          AND g.gap_status NOT IN ('RESOLVED','CLOSED')
        GROUP BY p.id,p.product_task_no,p.open_gap_count
        HAVING p.open_gap_count<>COUNT(g.id)
        """);
    addRows(issues, "SUBMITTED_DRAFT_NOT_VALIDATED", "ERROR", """
        SELECT d.id target_id,p.product_task_no task_no,NULL oa_no,NULL item_id,
               CONCAT('价格草稿状态=',d.draft_status,'，校验状态=',d.validation_status) message
        FROM lp_quote_price_draft d
        JOIN lp_quote_collaboration_product_task p ON p.id=d.product_task_id
        WHERE d.draft_status IN ('SUBMITTED','APPROVED','PUBLISHED')
          AND d.validation_status<>'PASSED'
        """);
    addPublishedWithoutFormal(issues);
    addRows(issues, "READY_WITH_OPEN_GAP", "ERROR", """
        SELECT p.id target_id,p.product_task_no task_no,l.oa_no,l.oa_form_item_id item_id,
               CONCAT('报价关联已READY，但产品仍有 ',p.open_gap_count,' 个开放缺口') message
        FROM lp_quote_collaboration_quote_link l
        JOIN lp_quote_collaboration_product_task p ON p.id=l.product_task_id
        WHERE l.active_flag=1 AND l.link_status='READY' AND p.open_gap_count>0
        """);
    addRows(issues, "APPROVED_RESULT_EXPIRED", "WARN", """
        SELECT r.id target_id,p.product_task_no task_no,NULL oa_no,NULL item_id,
               CONCAT('审核结果已超过有效期：',r.valid_until) message
        FROM lp_quote_collaboration_approved_result r
        JOIN lp_quote_collaboration_product_task p ON p.id=r.source_product_task_id
        WHERE r.result_status='ACTIVE' AND r.valid_until<CURRENT_DATE
        """);
    addRows(issues, "WORKBENCH_STATE_MISMATCH", "ERROR", """
        SELECT p.id target_id,p.product_task_no task_no,l.oa_no,l.oa_form_item_id item_id,
               '协作状态已进入核算，但不存在对应核算BOM构建批次' message
        FROM lp_quote_collaboration_product_task p
        JOIN lp_quote_collaboration_quote_link l ON l.product_task_id=p.id AND l.active_flag=1
        WHERE p.task_status IN ('COSTING','COMPLETED')
          AND NOT EXISTS (
            SELECT 1 FROM lp_quote_bom_status s
            WHERE s.oa_form_item_id=l.oa_form_item_id
              AND s.costing_build_batch_id IS NOT NULL
          )
        """);
    return new Reconciliation(issues.size(), List.copyOf(issues));
  }

  @Transactional(readOnly = true)
  public PublicationFailures publicationFailures() {
    List<PublicationFailure> items = jdbc.query("""
        SELECT r.id,r.review_no,r.collaboration_id,t.oa_no,r.review_status,t.master_status,
               r.publish_batch_no,r.updated_at
        FROM lp_quote_collaboration_review r
        JOIN lp_quote_collaboration_task t ON t.id=r.collaboration_id
        WHERE r.review_status='FAILED' OR t.master_status='PUBLISH_FAILED'
        ORDER BY r.updated_at DESC,r.id DESC LIMIT 500
        """, (rs, row) -> new PublicationFailure(rs.getLong("id"), rs.getString("review_no"),
        rs.getLong("collaboration_id"), rs.getString("oa_no"), rs.getString("review_status"),
        rs.getString("master_status"), rs.getString("publish_batch_no"),
        rs.getTimestamp("updated_at").toLocalDateTime()));
    return new PublicationFailures(items.size(), items);
  }

  @Transactional
  public CompensationResult invalidateApprovedResult(Long id, CompensationRequest request) {
    requireRequest(request);
    Map<String, Object> row = single("""
        SELECT r.result_status,r.source_product_task_id,p.product_task_no,t.oa_no,p.task_version
        FROM lp_quote_collaboration_approved_result r
        JOIN lp_quote_collaboration_product_task p ON p.id=r.source_product_task_id
        JOIN lp_quote_collaboration_task t ON t.id=p.origin_collaboration_id
        WHERE r.id=?
        """, id);
    String before = String.valueOf(row.get("result_status"));
    CompensationResult replay = replay(request.requestId(), "INVALIDATE_APPROVED_RESULT", id);
    if (replay != null) return replay;
    if (!"ACTIVE".equals(before)) throw new IllegalStateException("只有ACTIVE审核结果可以手工失效");
    CollaborationActor actor = actorProvider.current();
    if (jdbc.update("""
        UPDATE lp_quote_collaboration_approved_result
        SET result_status='INVALIDATED',invalid_reason=?,invalidated_at=NOW(),
            updated_by=?,updated_by_name=?,updated_at=NOW()
        WHERE id=? AND result_status='ACTIVE'
        """, request.reason().trim(), actor.userId(), actor.userName(), id) != 1)
      throw new IllegalStateException("审核结果状态已变化，请刷新后重试");
    audit(request, "INVALIDATE_APPROVED_RESULT", "APPROVED_RESULT", id, before, "INVALIDATED",
        String.valueOf(row.get("oa_no")), null, String.valueOf(row.get("product_task_no")),
        ((Number) row.get("task_version")).intValue(), null);
    return new CompensationResult(request.requestId(), "INVALIDATE_APPROVED_RESULT", id,
        before, "INVALIDATED", false);
  }

  private void addPublishedWithoutFormal(List<Issue> issues) {
    List<Map<String, Object>> drafts = jdbc.queryForList("""
        SELECT d.id,d.published_source_table,d.published_source_id,p.product_task_no
        FROM lp_quote_price_draft d JOIN lp_quote_collaboration_product_task p ON p.id=d.product_task_id
        WHERE d.draft_status='PUBLISHED'
        """);
    for (Map<String, Object> draft : drafts) {
      String table = String.valueOf(draft.get("published_source_table"));
      Number sourceId = (Number) draft.get("published_source_id");
      boolean missing = !FORMAL_TABLES.contains(table) || sourceId == null
          || jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class,
              sourceId.longValue()) == 0;
      if (missing) issues.add(new Issue("PUBLISHED_WITHOUT_FORMAL", "ERROR",
          ((Number) draft.get("id")).longValue(), String.valueOf(draft.get("product_task_no")),
          null, null, "草稿已标记发布，但正式价格记录不存在"));
    }
  }

  private void addRows(List<Issue> target, String type, String severity, String sql) {
    target.addAll(jdbc.query(sql, (rs, row) -> new Issue(type, severity,
        rs.getObject("target_id", Long.class), rs.getString("task_no"), rs.getString("oa_no"),
        rs.getObject("item_id", Long.class), rs.getString("message"))));
  }

  private Map<String, Object> single(String sql, Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
    if (rows.isEmpty()) throw new IllegalArgumentException("补偿目标不存在");
    return rows.get(0);
  }

  private CompensationResult replay(String key, String action, Long targetId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT action_type,target_id,before_status,after_status
        FROM lp_quote_collaboration_admin_action WHERE request_key=?
        """, key);
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.get(0);
    if (!action.equals(row.get("action_type"))
        || targetId.longValue() != ((Number) row.get("target_id")).longValue()) {
      throw new IllegalStateException("requestId已被其他补偿操作使用");
    }
    return new CompensationResult(key, action, targetId,
        String.valueOf(row.get("before_status")), String.valueOf(row.get("after_status")), true);
  }

  private void audit(CompensationRequest request, String action, String targetType, Long targetId,
      String before, String after, String oaNo, Long itemId, String taskNo,
      Integer version, String batch) {
    CollaborationActor actor = actorProvider.current();
    try {
      jdbc.update("""
          INSERT INTO lp_quote_collaboration_admin_action
            (request_key,action_type,target_type,target_id,reason,before_status,after_status,
             trace_id,oa_no,oa_form_item_id,task_no,target_version,publish_batch_no,
             operator_user_id,operator_name)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, request.requestId().trim(), action, targetType, targetId, request.reason().trim(),
          before, after, request.requestId().trim(), oaNo, itemId, taskNo, version, batch,
          actor.userId(), actor.userName());
    } catch (DuplicateKeyException duplicate) {
      throw new IllegalStateException("补偿请求已并发执行，请刷新查看结果");
    }
  }

  private static void requireRequest(CompensationRequest request) {
    if (request == null || !StringUtils.hasText(request.requestId()))
      throw new IllegalArgumentException("requestId不能为空");
    if (!StringUtils.hasText(request.reason())) throw new IllegalArgumentException("补偿原因不能为空");
  }
}
