package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaMessageRepository;
import com.sanhua.marketingcost.integration.technicaldata.OaDeliveryUnknownException;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** OA 回执只推进其对应的人员分支，其他人的草稿、审批和版本保持独立。 */
@Service
public class TechnicalDataOaSubmissionLifecycle {
  private final QuoteTechnicalDataRepository products;
  private final TechnicalDataParticipantVersions versions;
  private final QuoteTechSubmissionMapper submissions;
  private final QuoteTechTaskMapper tasks;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataOaRecipientRepository recipients;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataAuditLogService audit;
  private final TechnicalDataPricePublication prices;

  public TechnicalDataOaSubmissionLifecycle(QuoteTechnicalDataRepository products, TechnicalDataParticipantVersions versions,
      QuoteTechSubmissionMapper submissions, QuoteTechTaskMapper tasks, TechnicalDataOaWorkflowRepository workflow,
      TechnicalDataOaRecipientRepository recipients, OaMessageRepository messages, OaMessageCodec codec, TechnicalDataAuditLogService audit, TechnicalDataPricePublication prices) {
    this.prices = prices;
    this.products = products; this.versions = versions; this.submissions = submissions; this.tasks = tasks;
    this.workflow = workflow; this.recipients = recipients; this.messages = messages; this.codec = codec; this.audit = audit;
  }

  /** 主动通知已证明OA受理：冻结快照已存在时，无须等待丢失的同步回执。 */
  public void confirmFromNotification(QuoteTechTask task, QuoteTechSubmission submission) {
    var person = requireCurrent(task, submission);
    if ("SENT".equals(submission.getSubmissionStatus())) return;
    if (!Set.of("PREPARED", "SENDING", "UNKNOWN").contains(submission.getSubmissionStatus())
        || submission.getOutboundMessageId() == null) throw conflict("资料提交没有可匹配的冻结发送记录");
    var product = products.lockProduct(submission.getProductId()).orElseThrow();
    var version = versions.verified(submission.getTechnicalVersionId(), product.getId());
    versions.transition(version, "SUBMITTED", 0);
    versions.state(product, person, version.getId(), "SUBMITTED");
    workflow.markSending(submission.getId());
    workflow.acceptSubmission(submission.getId(), workflow.findFlow(task.getOaFlowId()).externalFlowId());
    recipients.state(person.id(), submission.getId(), "PREPARED", "SUBMITTED", null);
    recipients.refreshTask(task.getId());
  }

  public void approve(QuoteTechTask task, QuoteTechSubmission submission, long messageId, TechnicalDataActor operator) {
    var person = requireCurrent(task, submission);
    var product = products.lockProduct(submission.getProductId()).orElseThrow();
    var version = versions.verified(submission.getTechnicalVersionId(), product.getId());
    workflow.decision(submission.getId(), "APPROVED", messageId);
    var approved = versions.transition(version, "APPROVED", operator.userId());
    versions.state(product, person, version.getId(), "APPROVED");
    if (person.processingModules().contains("PRICE")) prices.publish(task, product, approved);
    recipients.state(person.id(), submission.getId(), "SUBMITTED", "DONE", null);
    recipients.refreshTask(task.getId());
    versions.activate(product, operator.userId());
    audit.record(task, product, submission.getId(), "PERSON_APPROVED", "SUBMITTED", "APPROVED",
        person.name() + "第 " + submission.getSubmissionRound() + " 次提交获其部门领导通过", operator,
        "oa-message:" + messageId, "TD-PERSON-APPROVED:" + submission.getId());
  }

  public void returned(QuoteTechTask task, QuoteTechSubmission submission, long messageId, TechnicalDataActor operator, String reason) {
    var person = requireCurrent(task, submission);
    if (reason == null || reason.isBlank()) throw conflict("退回必须提供原因");
    workflow.decision(submission.getId(), "RETURNED", messageId);
    var version = versions.verified(submission.getTechnicalVersionId(), submission.getProductId());
    versions.transition(version, "RETURNED", operator.userId());
    restore(task, person, submission, operator.userId(), "SUBMITTED", reason);
    audit.record(task, null, submission.getId(), "PERSON_RETURNED", "SUBMITTED", "OPEN",
        person.name() + "：" + reason, operator, "oa-message:" + messageId, "TD-PERSON-RETURNED:" + submission.getId());
  }

  /** 财务定向退回经 OA 确认后调用；原 APPROVED 提交保留当时的审批结论。 */
  public void financeReturned(QuoteTechTask task, QuoteTechSubmission submission, long operatorId, String reason) {
    var person = requireCurrent(task, submission);
    if (!"APPROVED".equals(submission.getSubmissionStatus()) || reason == null || reason.isBlank()) throw conflict("财务退回须关联已批准提交和原因");
    restore(task, person, submission, operatorId, "RETURN_PENDING", reason);
  }

  private void restore(QuoteTechTask task, Recipient person, QuoteTechSubmission submission, long operator, String from, String reason) {
    var product = products.lockProduct(submission.getProductId()).orElseThrow();
    // OA 已确认退回：同一事务先恢复人员办理状态，再恢复选中板块到当前草稿。
    // 旧数据库仍以任务汇总状态保护已提交模块，不能在 APPROVED/SUBMITTED 状态下恢复编辑内容。
    recipients.state(person.id(), submission.getId(), from, "OPEN", reason);
    recipients.refreshTask(task.getId());
    if ("RETURN_PENDING".equals(from)) versions.restoreApproved(product, person, operator);
    else versions.restore(product, person, submission.getTechnicalVersionId(), operator);
    workflow.invalidateFinance(task.getOaFlowId());
  }

  public Recipient requireCurrent(QuoteTechTask task, QuoteTechSubmission submission) {
    if (task == null || submission == null || !Integer.valueOf(1).equals(task.getActiveFlag())
        || !Objects.equals(task.getId(), submission.getTaskId()) || submission.getRecipientId() == null) throw conflict("提交不属于当前活动产品的人员分支");
    var person = recipients.findById(submission.getRecipientId());
    if (person == null || !person.active() || person.taskId() != task.getId()
        || person.userId() != submission.getAssigneeUserId() || !Objects.equals(person.latestSubmissionId(), submission.getId())
        || person.submissionRound() != submission.getSubmissionRound()) throw conflict("提交已不是此人的当前办理轮次");
    var product = products.lockProduct(submission.getProductId()).orElseThrow();
    if (!Objects.equals(product.getTaskId(), task.getId()) || !Objects.equals(product.getOaFormItemId(), task.getOaFormItemId())
        || !Objects.equals(product.getAccountingMonth(), task.getAccountingMonth())) throw conflict("提交版本与产品、核算月份不一致");
    return person;
  }

  private OaIntegrationException conflict(String message) { return OaIntegrationException.conflict("OA_SUBMISSION_STATE_CONFLICT", message); }
}
