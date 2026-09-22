package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.integration.technicaldata.OaDeliveryUnknownException;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaIntegrationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 认领先提交，HTTP 在事务外；回执与业务状态在同一事务确认，崩溃后先查询原请求。 */
@Component
public class OaOutboxProcessor {
  private static final Logger log = LoggerFactory.getLogger(OaOutboxProcessor.class);
  private final com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService quotes;
  private final com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper technicalSubmissions;
  private final OaIntegrationProperties properties;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaIntegrationService dispatch;
  private final TechnicalDataOaSubmissionLifecycle submissions;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TransactionTemplate transaction;
  private final com.sanhua.marketingcost.service.technicaldata.TechnicalDataWorkflowService finance;

  public OaOutboxProcessor(OaIntegrationProperties properties, OaMessageRepository messages, OaMessageCodec codec,
      TechnicalDataOaGateway gateway, TechnicalDataOaIntegrationService dispatch,
      TechnicalDataOaSubmissionLifecycle submissions, TechnicalDataOaWorkflowRepository workflow,
      PlatformTransactionManager transactionManager, com.sanhua.marketingcost.service.technicaldata.TechnicalDataWorkflowService finance, com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService quotes,
      com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper technicalSubmissions) {
    this.quotes = quotes;
    this.technicalSubmissions = technicalSubmissions;
    this.properties = properties; this.messages = messages; this.codec = codec; this.gateway = gateway;
    this.dispatch = dispatch; this.submissions = submissions; this.workflow = workflow;
    this.finance = finance;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Scheduled(fixedDelayString = "${integration.oa.outbound.poll-interval-ms:1000}")
  public void poll() {
    if (!properties.isWorkerEnabled() || !gateway.enabled()) return;
    try {
      for (int index = 0; index < properties.getBatchSize() && processNext(); index++) { /* 有界发送批次 */ }
    } catch (RuntimeException exception) {
      log.warn("OA outbox polling deferred: {}", exception.getClass().getSimpleName());
    }
  }

  public boolean processNext() {
    var message = transaction.execute(status -> {
      var claimed = messages.claimOutgoing(gateway.peer(), properties.getLeaseSeconds(), properties.getMaxAttempts());
      if (claimed != null && "TECH_SUBMISSION".equals(claimed.interfaceType())) {
        workflow.markSending(technicalSubmissionId(claimed.id()));
      }
      return claimed;
    });
    if (message == null) return false;
    try {
      var operation = TechnicalDataOaGateway.Operation.valueOf(message.interfaceType());
      var result = message.attemptCount() > 1 ? gateway.query(operation, message.requestId()).orElse(null) : null;
      if (result == null) result = gateway.send(operation, message.rawPayload());
      var receipt = result;
      transaction.executeWithoutResult(status -> {
        var owned = messages.lockOwned(message.id(), message.leaseToken());
        if (owned == null) return;
        if (operation == TechnicalDataOaGateway.Operation.TASK_DISPATCH) dispatch.acceptReceipt(owned, receipt);
        else if (operation == TechnicalDataOaGateway.Operation.TECH_SUBMISSION) submissions.acceptReceipt(owned, receipt);
        else if (operation == TechnicalDataOaGateway.Operation.TECH_RETURN) finance.acceptReturn(owned, receipt);
        else quotes.acceptReceipt(owned, receipt);
        messages.finish(owned, receipt.accepted() ? "PROCESSED" : "REJECTED", codec.write(receipt.result()),
            "DELIVERY", receipt.errorCode(), receipt.accepted() ? null : "OA 明确拒绝了此请求", 0);
      });
    } catch (OaDeliveryUnknownException exception) {
      unconfirmed(message, "DELIVERY_UNKNOWN", exception.getMessage(), true);
    } catch (RuntimeException exception) {
      // 发送可能已成功，存储/应用失败也不能伪装成 OA 明确拒绝。
      String detail = failureDetail(exception);
      log.warn("OA outgoing message {} not confirmed: {}", message.id(), detail);
      unconfirmed(message, "DELIVERY_CONFIRMATION_FAILED", "发送回执尚未成功写入业务状态：" + detail + "；请按请求编号核实", true);
    }
    return true;
  }

  static String failureDetail(RuntimeException exception) {
    if (exception instanceof org.springframework.dao.DataAccessException data
        && data.getMostSpecificCause() instanceof java.sql.SQLException sql) {
      String constraint = java.util.Objects.toString(sql.getMessage(), "").split(":", 2)[0];
      return "SQLSTATE=" + sql.getSQLState() + ", DB_CODE=" + sql.getErrorCode()
          + (constraint.matches("[A-Z][A-Z0-9_]{1,100}") ? ", " + constraint : "");
    }
    return exception.getClass().getSimpleName();
  }

  private void unconfirmed(OaMessageRepository.Message message, String code, String reason, boolean retryable) {
    transaction.executeWithoutResult(status -> {
      var owned = messages.lockOwned(message.id(), message.leaseToken());
      if (owned == null) return;
      if ("TASK_DISPATCH".equals(owned.interfaceType())) dispatch.markUnconfirmed(owned, false, reason);
      else if ("TECH_SUBMISSION".equals(owned.interfaceType())) workflow.markUnknown(technicalSubmissionId(owned.id()));
      else if (owned.interfaceType().startsWith("QUOTE_")) quotes.unknown(owned, reason);
      boolean retry = retryable && owned.attemptCount() < properties.getMaxAttempts();
      messages.finish(owned, retry ? "RECEIVED" : "FAILED", null, "VERIFY", code, reason,
          retry ? Math.min(60, owned.attemptCount() * 3) : 0);
    });
  }

  private long technicalSubmissionId(long messageId) {
    var submission = technicalSubmissions.selectByOutboundMessage(messageId);
    if (submission == null) throw new IllegalStateException("发送记录未关联资料提交：" + messageId);
    return submission.getId();
  }
}
