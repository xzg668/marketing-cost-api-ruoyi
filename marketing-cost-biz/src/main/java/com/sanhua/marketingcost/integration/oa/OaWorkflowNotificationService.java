package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 成功回执意味着通知和业务修改在同一事务中已保存；整单锁避免通知与提交交叉修改。 */
@Service
public class OaWorkflowNotificationService {
  private final OaMessageRepository messages;
  private final OaWorkflowNotificationRepository repository;
  private final OaWorkflowNotificationHandler handler;
  private final OaMessageCodec codec;
  public OaWorkflowNotificationService(OaMessageRepository messages, OaWorkflowNotificationRepository repository,
      OaWorkflowNotificationHandler handler, OaMessageCodec codec) {
    this.messages = messages; this.repository = repository; this.handler = handler; this.codec = codec;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public JsonNode receive(OaPeer peer, String raw) {
    try (var call = OaInterfaceLog.start("I04_I08_WORKFLOW_NOTIFY")) {
      try {
        var event = OaWorkflowNotification.parse(codec.readQuotation(raw));
        call.field("sourceSystem", peer.sourceSystem()).field("environment", peer.environment())
            .field("requestId", event.requestId()).field("eventType", event.eventType())
            .field("employeeCount", event.employeeNos().size());
        var document = repository.document(peer, event.requestId());
        if (document == null) throw conflict("QUOTE_NOT_FOUND", "未找到报价需求，requestId 必须为 I01 推送的原 OA 流程 ID");
        if (!peer.businessUnits().contains(document.businessUnit())) throw conflict("FORBIDDEN_BUSINESS_UNIT", "调用方无权处理该报价单");
        repository.lockForm(document.id());
        var flow = repository.lock(peer, event.requestId());
        var scope = handler.scope(event, document);
        // 原流程 ID 不是通知 ID。按本地本轮提交范围去重，避免 I01/I04/I08 互相占用 requestId。
        String hash = codec.canonicalHash(Map.of("notice", event, "scope", scope));
        var envelope = new OaMessageCodec.Envelope(5, "NOTIFY:" + hash,
            OffsetDateTime.now().toString(), codec.readQuotation(raw), raw, codec.canonicalHash(event));
        var message = messages.receive(peer, OaMessageCodec.InterfaceType.WORKFLOW_EVENT, envelope);
        call.field("messageId", message.id()).field("formId", document.id());
        if ("PROCESSED".equals(message.status())) {
          call.result("IDEMPOTENT_REPLAY", null, "0");
          return codec.read(message.resultJson());
        }
        if (Set.of("COMPLETED", "CANCELLED", "TERMINATED").contains(flow.state())) {
          throw conflict("FLOW_CLOSED", "流程已经结束，不能再次退回或审批");
        }
        if (handler.apply(message, event, document, flow, scope)) {
          repository.associate(flow, message.id(), hash);
        }
        String reply = codec.write(OaWorkflowReply.success());
        repository.complete(message.id(), reply);
        call.result("HANDLED", null, "0");
        return codec.read(reply);
      } catch (RuntimeException exception) {
        call.failure(exception);
        throw exception;
      }
    }
  }
  private static OaIntegrationException conflict(String code, String message) {
    return OaIntegrationException.conflict(code, message);
  }
}
