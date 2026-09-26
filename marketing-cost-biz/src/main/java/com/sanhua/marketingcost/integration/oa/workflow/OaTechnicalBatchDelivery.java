package com.sanhua.marketingcost.integration.oa.workflow;

import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 发送前持久化占用；网络不在事务内。重复点击只读取原结果，不自动重发 OA 写请求。 */
@Service
public class OaTechnicalBatchDelivery {

  private final OaTechnicalBatchRepository batches;
  private final OaWorkflowClient workflow;
  private final OaTechnicalSubmissionClient submissions;
  private final TransactionTemplate transaction;

  public OaTechnicalBatchDelivery(
    OaTechnicalBatchRepository batches,
    OaWorkflowClient workflow,
    OaTechnicalSubmissionClient submissions,
    PlatformTransactionManager manager
  ) {
    this.batches = batches;
    this.workflow = workflow;
    this.submissions = submissions;
    this.transaction = new TransactionTemplate(manager);
  }

  public OaTechnicalBatchRepository.Batch send(String id) {
    return send(id, batch -> {});
  }

  public OaTechnicalBatchRepository.Batch send(String id, java.util.function.Consumer<OaTechnicalBatchRepository.Batch> validate) {
    try (var call = OaInterfaceLog.start("TECHNICAL_BATCH_DELIVERY")) {
      call.field("batchId", id);
      return send(id, call, validate);
    }
  }

  private OaTechnicalBatchRepository.Batch send(String id, OaInterfaceLog.Call call,
      java.util.function.Consumer<OaTechnicalBatchRepository.Batch> validate) {
    boolean claimed = Boolean.TRUE.equals(transaction.execute(status -> {
      var prepared = batches.find(id, false);
      if (!"PREPARED".equals(prepared.status())) return false;
      try {
        validate.accept(prepared);
      } catch (IllegalArgumentException invalid) {
        if (batches.startSending(id)) batches.received(id, new OaWorkflowResult(null,
            OaWorkflowResult.Status.NOT_SENT, null, "BUSINESS_STATE_CHANGED", invalid.getMessage(),
            prepared.request().path("requestId").asText(), null, 0));
        return false;
      }
      return batches.startSending(id);
    }));
    var batch = batches.find(id, false);
    call.field("interfaceType", batch.operation()).field("userId", batch.actorId()).business(batch.request());
    if (claimed) {
      var body = batch.request();
      OaWorkflowResult result;
      try {
        result = "I03".equals(batch.operation())
          ? submissions.submit(
              new OaTechnicalSubmissionClient.Request(
                body.path("requestId").asText(),
                body.path("userid").asText(),
                body.path("remark").asText(),
                body.at("/formData/dataDetails/0/content").asText()
              )
            )
          : workflow.submit(body);
      } catch (RuntimeException exception) {
        call.failure(exception);
        // 未知异常可能发生在发送之后，保留未确认结果，不能当作未发送再试。
        result = new OaWorkflowResult(
          null,
          OaWorkflowResult.Status.UNKNOWN,
          null,
          "OA_DELIVERY_EXCEPTION",
          "OA调用结果未确认，请核实原流程",
          body.path("requestId").asText(),
          null,
          0
        );
      }
      var received = result;
      transaction.executeWithoutResult(status -> batches.received(id, received));
      batch = batches.find(id, false);
    }
    call.result(
      batch.status(),
      batch.result() == null ? null : batch.result().httpStatus(),
      batch.result() == null ? null : batch.result().errorCode()
    );
    return batch;
  }
}
