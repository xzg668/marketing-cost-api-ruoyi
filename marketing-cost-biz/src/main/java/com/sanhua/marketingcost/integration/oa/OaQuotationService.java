package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** I01 正式需求仅保存一次；原文、报价数据及回执在同一事务提交，超时重发不重复建单。 */
@Service
public class OaQuotationService {
  private static final int MAX_RECEIVE_ATTEMPTS = 3;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final OaQuotationRequestMapper mapper;
  private final OaQuoteIngestHandler handler;
  private final TransactionTemplate transaction;

  public OaQuotationService(
      OaMessageRepository messages,
      OaMessageCodec codec,
      OaQuotationRequestMapper mapper,
      OaQuoteIngestHandler handler,
      PlatformTransactionManager transactionManager) {
    this.messages = messages;
    this.codec = codec;
    this.mapper = mapper;
    this.handler = handler;
    this.transaction = new TransactionTemplate(transactionManager);
    // 每次重试必须重新开启完整事务，不能在已被 MySQL 回滚的事务中继续写入。
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public JsonNode receive(OaPeer peer, String raw) {
    try (var call = OaInterfaceLog.start("I01_QUOTATION_RECEIVE")) {
      if (peer != null) call.field("sourceSystem", peer.sourceSystem()).field("environment", peer.environment());
      try {
        JsonNode result = receiveWithRetry(peer, raw, call);
        call.result("0".equals(result.path("code").asText()) ? "HANDLED" : "REJECTED", null, result.path("code").asText());
        return result;
      } catch (RuntimeException exception) {
        call.failure(exception);
        throw exception;
      }
    }
  }

  private JsonNode receiveWithRetry(OaPeer peer, String raw, OaInterfaceLog.Call call) {
    for (int attempt = 1; ; attempt++) {
      call.field("attempt", attempt);
      try {
        // execute 返回时事务已经提交，之后才能记录成功并向 OA 返回回执。
        return transaction.execute(status -> receive(peer, raw, call));
      } catch (DataAccessException failure) {
        if (attempt >= MAX_RECEIVE_ATTEMPTS || !isRetryableLockFailure(failure)) throw failure;
        long delayMillis = ThreadLocalRandom.current().nextLong(40, 121) * attempt;
        try (var retry = OaInterfaceLog.start("I01_QUOTATION_TRANSACTION_RETRY")) {
          retry.field("attempt", attempt).failure(failure);
          retry.result("RETRY", null, "SQL_LOCK_CONFLICT");
        }
        try {
          Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw failure;
        }
      }
    }
  }

  private boolean isRetryableLockFailure(DataAccessException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      // 只重试已回滚的死锁/锁等待；断连、约束错误或未知提交结果交回原错误处理。
      if (cause instanceof SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
        return true;
      }
    }
    return false;
  }

  private JsonNode receive(OaPeer peer, String raw, OaInterfaceLog.Call call) {
    JsonNode root = codec.readQuotation(raw);
    call.business(root).business(root.path("workflowState"));
    var mapped = mapper.map(root, peer);
    call.field("itemCount", mapped.lines().size());
    var envelope =
        new OaMessageCodec.Envelope(
            3,
            mapped.requestId(),
            OffsetDateTime.now().toString(),
            root,
            raw,
            codec.canonicalHash(root));
    OaMessageRepository.Message message;
    try {
      message = messages.receive(peer, OaMessageCodec.InterfaceType.QUOTE_REQUEST, envelope);
    } catch (OaIntegrationException ex) {
      if ("REQUEST_CONTENT_CONFLICT".equals(ex.code())) {
        throw OaIntegrationException.conflict("IDEMPOTENCY_CONFLICT", "该OA单据已接收，重发内容与原需求不同，不能覆盖；请核对requestId和原报文");
      }
      throw ex;
    }
    if (message.schemaVersion() != 3 || !"QUOTE_REQUEST".equals(message.interfaceType()))
      throw OaIntegrationException.conflict("IDEMPOTENCY_CONFLICT", "requestId已用于其他接口");
    call.field("messageId", message.id());
    if (message.resultJson() != null && "PROCESSED".equals(message.status())) {
      call.field("stage", "IDEMPOTENT_REPLAY");
      return codec.read(message.resultJson());
    }
    // receive 的唯一键写入已持有行锁；事务提交前后台接收箱看不到尚未完成的新记录。
    var result = handler.handle(message, mapped.quote());
    Map<String, String> ids =
        result.items().stream()
            .collect(
                Collectors.toMap(
                    item -> item.get("externalLineId").toString(),
                    item -> item.get("oaFormItemId").toString()));
    List<OaQuotationResponse.ItemMapping> mappings = new ArrayList<>();
    for (var line : mapped.lines())
      mappings.add(
          new OaQuotationResponse.ItemMapping(
              line.tableKey(), line.rowId(), ids.get(line.externalLineId())));
    boolean blocked = !mapped.issues().isEmpty();
    var response =
        new OaQuotationResponse(
            "0",
            blocked ? "需求已保存，存在核算输入缺口" : "需求已保存，待执行核算资料检查",
            new OaQuotationResponse.Data(
                "SUCCEEDED",
                Long.toString(result.oaFormId()),
                "/ingest/quote-requests/"
                    + URLEncoder.encode(result.oaNo(), StandardCharsets.UTF_8).replace("+", "%20"),
                mappings,
                blocked ? "BLOCKED" : "PENDING",
                mapped.issues(),
                null));
    String json = codec.write(response);
    messages.completeQuotation(message.id(), json);
    return codec.read(json);
  }
}
