package com.sanhua.marketingcost.integration.oa.workflow;

import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** I05 的两次有序发送。每步发送前占用、发送后持久化回执，网络调用不占数据库事务。 */
@Service
public class OaTechnicalReturnDelivery {
  private final OaTechnicalBatchRepository batches;
  private final OaTechnicalReturnClient client;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  public OaTechnicalReturnDelivery(OaTechnicalBatchRepository batches, OaTechnicalReturnClient client,
      JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.batches = batches;
    this.client = client;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
  }

  public void send(String id, Consumer<OaTechnicalBatchRepository.Batch> validate) {
    for (int step = 0; step < 2; step++) {
      var sending = claim(id, null, validate);
      if (sending == null) return;
      var result = deliver(sending);
      transaction.executeWithoutResult(tx -> batches.receiveReturnStep(sending, result));
      if (!"PEOPLE".equals(sending.returnStep()) || result.status() != OaWorkflowResult.Status.SUCCESS) return;
    }
  }

  /** 只续办明确失败/尚未发送的第二步；客户端用看到的发送次数防止重复重试。 */
  public void retryRejection(String id, int expectedAttempt, Consumer<OaTechnicalBatchRepository.Batch> validate) {
    var sending = claim(id, expectedAttempt, validate);
    if (sending == null) return;
    var result = deliver(sending);
    transaction.executeWithoutResult(tx -> batches.receiveReturnStep(sending, result));
  }

  private OaTechnicalBatchRepository.Batch claim(String id, Integer expectedAttempt,
      Consumer<OaTechnicalBatchRepository.Batch> validate) {
    return transaction.execute(tx -> {
      var original = batches.find(id, false);
      if (original == null || !"I05".equals(original.operation())) throw new IllegalArgumentException("退回记录不存在");
      // 与任务完成、流程通知统一按单据再批次的顺序加锁。
      jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, original.formId());
      var batch = batches.find(id, true);
      boolean ready = expectedAttempt == null
          ? "PREPARED".equals(batch.status()) && "PEOPLE".equals(batch.returnStep())
              || "REJECT_READY".equals(batch.status()) && "REJECT".equals(batch.returnStep())
          : batch.returnAttempt() == expectedAttempt && "REJECT".equals(batch.returnStep())
              && Set.of("RETURN_FAILED", "REJECT_READY").contains(batch.status());
      if (!ready) return null;
      if (batch.rejectRequest() == null || "REJECT".equals(batch.returnStep())
          && (batch.peopleResult() == null || batch.peopleResult().status() != OaWorkflowResult.Status.SUCCESS)) {
        throw new IllegalStateException("退回记录缺少节点报文或第一步成功回执，请核实原请求");
      }
      try {
        validate.accept(batch);
      } catch (IllegalArgumentException invalid) {
        if (batches.startReturnStep(batch)) {
          var sending = batches.find(id, false);
          batches.receiveReturnStep(sending, new OaWorkflowResult(null, OaWorkflowResult.Status.NOT_SENT,
              null, "BUSINESS_STATE_CHANGED", invalid.getMessage(), batch.request().path("requestId").asText(), null, 0));
        }
        return null;
      }
      return batches.startReturnStep(batch) ? batches.find(id, false) : null;
    });
  }

  private OaWorkflowResult deliver(OaTechnicalBatchRepository.Batch batch) {
    try (var call = OaInterfaceLog.start("I05_RETURN_DELIVERY")) {
      call.field("batchId", batch.id()).field("step", batch.returnStep()).field("attempt", batch.returnAttempt());
      var body = "PEOPLE".equals(batch.returnStep()) ? batch.request() : batch.rejectRequest();
      call.business(body);
      try {
        var result = "PEOPLE".equals(batch.returnStep()) ? client.updateTechnicians(body) : client.reject(body);
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException error) {
        call.failure(error);
        // 调用后抛错不能证明 OA 未执行，保留本步骤，不自动重发。
        return new OaWorkflowResult(null, OaWorkflowResult.Status.UNKNOWN, null, "OA_DELIVERY_EXCEPTION",
            "OA调用结果未确认，请核实原流程", body.path("requestId").asText(), null, 0);
      }
    }
  }
}
