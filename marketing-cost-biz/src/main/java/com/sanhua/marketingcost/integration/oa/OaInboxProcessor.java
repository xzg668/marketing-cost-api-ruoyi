package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaWorkflowHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据库接收箱就是持久队列；认领先提交，业务及最终回执再在同一事务提交。 */
@Component
public class OaInboxProcessor {
  private static final Logger log = LoggerFactory.getLogger(OaInboxProcessor.class);
  private final com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService quotes;
  private final OaIntegrationProperties properties;
  private final OaMessageRepository repository;
  private final OaMessageCodec codec;
  private final TechnicalDataOaWorkflowHandler workflowHandler;
  private final TransactionTemplate transaction;

  public OaInboxProcessor(OaIntegrationProperties properties, OaMessageRepository repository,
      OaMessageCodec codec,
      PlatformTransactionManager transactionManager, TechnicalDataOaWorkflowHandler workflowHandler, com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService quotes) {
    this.quotes = quotes;
    this.properties = properties; this.repository = repository; this.codec = codec;
    this.workflowHandler = workflowHandler;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Scheduled(fixedDelayString = "${integration.oa.poll-interval-ms:1000}")
  public void poll() {
    if (!properties.isWorkerEnabled() || properties.getMode() == OaIntegrationProperties.Mode.DISABLED) return;
    try {
      for (int i = 0; i < properties.getBatchSize() && processNext(); i++) { /* 有界批次 */ }
    } catch (RuntimeException ex) {
      // 不打印 SQL、原报文或认证信息；租约超时后下一轮会恢复未完成记录。
      log.warn("OA inbox polling deferred: {}", ex.getClass().getSimpleName());
    }
  }

  public boolean processNext() {
    var claimed = transaction.execute(status -> repository.claim(properties.getEnvironment(),
        properties.getLeaseSeconds(), properties.getMaxAttempts()));
    if (claimed == null) return false;
    try {
      transaction.executeWithoutResult(status -> process(claimed));
    } catch (OaWorkflowNotReadyException ex) {
      fail(claimed, "WAITING_HANDLER", "BUSINESS", "WAITING_SUBMISSION", ex.getMessage(), 0);
    } catch (OaIntegrationException ex) {
      fail(claimed, "REJECTED", ex.stage(), ex.code(), ex.getMessage(), 0);
    } catch (TransientDataAccessException ex) {
      boolean retry = claimed.attemptCount() < properties.getMaxAttempts();
      fail(claimed, retry ? "RECEIVED" : "FAILED", "PERSIST", "TRANSIENT_STORAGE_FAILURE",
          "数据库暂时不可用或发生并发冲突", Math.min(60, claimed.attemptCount() * 5));
    } catch (DataAccessException ex) {
      String diagnostic = storageDiagnostic(ex);
      log.warn("OA message {} storage failure: {}", claimed.id(), diagnostic);
      fail(claimed, "FAILED", "PERSIST", "STORAGE_CONSTRAINT_FAILURE", "数据未通过存储约束，" + diagnostic, 0);
    } catch (RuntimeException ex) {
      log.warn("OA message {} processing failed: {}", claimed.id(), ex.getClass().getSimpleName());
      fail(claimed, "FAILED", "PROCESS", "PROCESSING_FAILURE", "业务处理异常，请按请求编号排查", 0);
    }
    return true;
  }

  private void process(OaMessageRepository.Message claimed) {
    var message = repository.lockOwned(claimed.id(), claimed.leaseToken());
    if (message == null) return;
    var type = OaMessageCodec.InterfaceType.valueOf(message.interfaceType());
    var envelope = codec.decode(message.rawPayload(), message.peer(), type);
    if (type != OaMessageCodec.InterfaceType.WORKFLOW_EVENT) {
      throw OaIntegrationException.invalid("OBSOLETE_QUOTE_CONTRACT", "报价需求请按I01完整报文重新推送");
    }
    var payload = envelope.schemaVersion() == 1 ? envelope.payload() : envelope.payload().path("event");
    var result = "QUOTE_RETURNED".equals(payload.path("eventType").asText())
        ? quotes.returned(message, payload)
        : workflowHandler.handle(message, OaWorkflowEvent.map(envelope.schemaVersion(), envelope.payload()));
    repository.finish(message, "PROCESSED", codec.write(result), "BUSINESS", null, null, 0);
  }

  private void fail(OaMessageRepository.Message claimed, String status, String stage, String code,
      String message, int retryDelaySeconds) {
    transaction.executeWithoutResult(tx -> {
      var owned = repository.lockOwned(claimed.id(), claimed.leaseToken());
      if (owned != null) repository.finish(owned, status, null, stage, code,
          message.length() > 512 ? message.substring(0, 512) : message, retryDelaySeconds);
    });
  }

  private String storageDiagnostic(DataAccessException exception) {
    Throwable cause = exception.getMostSpecificCause();
    if (cause instanceof java.sql.SQLException sql) {
      return "SQLState=" + sql.getSQLState() + ", DBCode=" + sql.getErrorCode();
    }
    return exception.getClass().getSimpleName();
  }
}
