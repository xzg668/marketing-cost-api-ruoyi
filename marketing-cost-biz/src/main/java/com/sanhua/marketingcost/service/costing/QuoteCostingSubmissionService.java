package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 核算采用的技术提交版本；本地确认不推动 OA，退回或重提后必须重新采用。 */
@Service
public class QuoteCostingSubmissionService {
  private final JdbcTemplate jdbc;
  private final TechnicalDataOaWorkflowRepository workflows;
  private final OaMessageCodec codec;

  public QuoteCostingSubmissionService(JdbcTemplate jdbc, TechnicalDataOaWorkflowRepository workflows,
      OaMessageCodec codec) {
    this.jdbc = jdbc;
    this.workflows = workflows;
    this.codec = codec;
  }

  public boolean hasPendingSubmissions(long formId) {
    return hasPendingSubmissions(formId, null);
  }

  public boolean hasPendingSubmissions(long formId, Long itemId) {
    // 已分派任务必须全部成功提交；公共来源后来补齐也不能绕过正在办理的任务。
    return jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_quote_tech_task t
          LEFT JOIN lp_oa_quote_document d ON d.oa_form_id=t.oa_form_id
          WHERE t.oa_form_id=? AND (? IS NULL OR t.oa_form_item_id=?)
            AND t.active_flag=1 AND t.task_status<>'UNASSIGNED' AND (
            t.task_status NOT IN ('SUBMITTED','APPROVED')
            OR NOT EXISTS(SELECT 1 FROM lp_quote_tech_oa_recipient r WHERE r.task_id=t.id AND r.active_flag=1)
            OR EXISTS(SELECT 1 FROM lp_quote_tech_oa_recipient r
              LEFT JOIN lp_quote_tech_submission s ON s.id=r.latest_submission_id
              WHERE r.task_id=t.id AND r.active_flag=1
                AND (r.todo_status NOT IN ('SUBMITTED','DONE') OR s.submission_status IS NULL
                  OR s.submission_status NOT IN ('SENT','APPROVED')
                  OR COALESCE(s.source_form_version,0)<>COALESCE(d.source_version,0)))
            OR EXISTS(SELECT 1 FROM lp_quote_tech_product p JOIN lp_quote_tech_module m ON m.product_id=p.id
              WHERE p.task_id=t.id AND p.active_flag=1 AND m.required_flag=1
                AND (m.module_status NOT IN ('SUBMITTED','APPROVED') OR m.current_version_id IS NULL))))
        """, Boolean.class, formId, itemId, itemId);
  }

  public void requireSubmitted(long formId) {
    requireSubmitted(formId, null);
  }

  public void requireSubmitted(long formId, Long itemId) {
    if (hasPendingSubmissions(formId, itemId)) {
      throw new IllegalArgumentException("本单技术资料尚未全部成功提交，或已退回待修改，暂不能核算或提交成本");
    }
  }

  /** 报价员开始检查资料后可处理辅料归类、价格调整；此时尚未确认整单可核算。 */
  public void beginReview(long formId, long actorId) {
    beginReview(formId, actorId, null);
  }

  public void beginReview(long formId, long actorId, Long itemId) {
    requireSubmitted(formId, itemId);
    for (long flowId : assignedFlows(formId, itemId)) {
      workflows.lockFlow(flowId);
      if (workflows.refreshFinance(flowId)) {
        jdbc.update("UPDATE lp_oa_technical_flow SET finance_user_id=?,updated_at=NOW(3) WHERE id=?", actorId, flowId);
      }
    }
  }

  /** 调用方持有报价单锁；所有检查通过后才记录报价员本次采用的提交版本。 */
  public void accept(long formId, long actorId) {
    accept(formId, actorId, null);
  }

  public void accept(long formId, long actorId, Long itemId) {
    requireSubmitted(formId, itemId);
    for (long flowId : assignedFlows(formId, itemId)) {
      workflows.lockFlow(flowId);
      workflows.refreshFinance(flowId);
      var flow = workflows.lockFlow(flowId);
      if (!flow.financeReady()) throw new IllegalArgumentException("技术资料尚未形成完整的提交版本，请检查补录内容");
      String fingerprint = codec.canonicalHash(workflows.submissionBasis(flowId));
      if (!fingerprint.equals(flow.confirmedFingerprint())) workflows.confirmFinance(flowId, fingerprint, actorId);
    }
  }

  /** 同一规则供单产品、整单后台任务及最终成本提交使用。 */
  public void requireAccepted(long formId) {
    requireAccepted(formId, null);
  }

  public void requireAccepted(long formId, Long itemId) {
    requireSubmitted(formId, itemId);
    for (long flowId : assignedFlows(formId, itemId)) {
      var flow = workflows.findFlow(flowId);
      String fingerprint = codec.canonicalHash(workflows.submissionBasis(flowId));
      if (!flow.financeReady() || !fingerprint.equals(flow.confirmedFingerprint())) {
        throw new IllegalArgumentException("技术资料已更新，请检查补录内容后点击核算");
      }
    }
  }

  private List<Long> assignedFlows(long formId) {
    return assignedFlows(formId, null);
  }

  private List<Long> assignedFlows(long formId, Long itemId) {
    return jdbc.queryForList("""
        SELECT DISTINCT oa_flow_id FROM lp_quote_tech_task
        WHERE oa_form_id=? AND (? IS NULL OR oa_form_item_id=?)
          AND active_flag=1 AND task_status<>'UNASSIGNED' AND oa_flow_id IS NOT NULL
        ORDER BY oa_flow_id
        """, Long.class, formId, itemId, itemId);
  }
}
