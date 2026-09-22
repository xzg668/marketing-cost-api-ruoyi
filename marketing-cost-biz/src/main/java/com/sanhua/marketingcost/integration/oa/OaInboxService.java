package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OaInboxService {
  public record Receipt(boolean received, boolean processed, long messageId, String requestId,
      String interfaceType, String status, int attemptCount, String errorStage, String errorCode,
      String errorMessage, JsonNode result, LocalDateTime receivedAt, LocalDateTime processedAt) {}
  private final OaMessageRepository repository;
  private final OaMessageCodec codec;

  public OaInboxService(OaMessageRepository repository, OaMessageCodec codec) {
    this.repository = repository; this.codec = codec;
  }

  @Transactional
  public Receipt receive(OaPeer peer, OaMessageCodec.InterfaceType type, String rawJson) {
    if (type != OaMessageCodec.InterfaceType.WORKFLOW_EVENT) {
      throw OaIntegrationException.invalid("UNSUPPORTED_INTERFACE", "报价需求请调用I01接收接口");
    }
    var envelope = codec.decode(rawJson, peer, type);
    return receipt(repository.receive(peer, type, envelope));
  }

  public Receipt query(OaPeer peer, String requestId) {
    var message = repository.find(peer, requestId);
    if (message == null) throw new OaIntegrationException(HttpStatus.NOT_FOUND, "QUERY", "RECEIPT_NOT_FOUND", "未找到此调用方的请求记录");
    return receipt(message);
  }

  private Receipt receipt(OaMessageRepository.Message message) {
    return new Receipt(true, "PROCESSED".equals(message.status()), message.id(), message.requestId(),
        message.interfaceType(), message.status(), message.attemptCount(), message.errorStage(),
        message.errorCode(), message.errorMessage(),
        message.resultJson() == null ? null : codec.read(message.resultJson()), message.receivedAt(), message.processedAt());
  }
}
