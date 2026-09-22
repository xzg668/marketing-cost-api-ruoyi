package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OA 主动通知在一次事务内处理；返回成功时，业务状态和原始报文都已保存。 */
@Service
public class OaWorkflowNotificationService {
  private final OaMessageRepository messages;
  private final OaWorkflowNotificationRepository repository;
  private final OaWorkflowNotificationHandler handler;
  private final OaMessageCodec codec;

  public OaWorkflowNotificationService(
      OaMessageRepository messages,
      OaWorkflowNotificationRepository repository,
      OaWorkflowNotificationHandler handler,
      OaMessageCodec codec) {
    this.messages = messages;
    this.repository = repository;
    this.handler = handler;
    this.codec = codec;
  }

  @Transactional
  public JsonNode receive(OaPeer peer, String raw) {
    JsonNode root = codec.readQuotation(raw);
    var event = OaWorkflowNotification.parse(root);
    var document = repository.document(peer, event.workflowRequestId());
    if (document == null) {
      throw conflict("QUOTE_NOT_FOUND", "未找到已接入的报价需求，请核对workflowRequestId，应为I01的requestId");
    }
    if (!peer.businessUnits().contains(document.businessUnit())) {
      throw conflict("FORBIDDEN_BUSINESS_UNIT", "调用方无权处理该报价单");
    }
    // 与最终提交、技术资料处理采用同一整单锁，防止通知覆盖并发提交。
    repository.lockForm(document.id());
    var flow = repository.lock(peer, event.workflowRequestId());
    var envelope = new OaMessageCodec.Envelope(
        4, event.requestId(), OffsetDateTime.now().toString(), root, raw, codec.canonicalHash(root));
    OaMessageRepository.Message message;
    try {
      message = messages.receive(peer, OaMessageCodec.InterfaceType.WORKFLOW_EVENT, envelope);
    } catch (OaIntegrationException ex) {
      if ("REQUEST_CONTENT_CONFLICT".equals(ex.code())) {
        throw conflict("IDEMPOTENCY_CONFLICT", "本次通知requestId已对应另一份报文，请核对原通知");
      }
      throw ex;
    }
    if (message.schemaVersion() != 4 || !"WORKFLOW_EVENT".equals(message.interfaceType())) {
      throw conflict("IDEMPOTENCY_CONFLICT", "requestId已用于其他接口");
    }
    if ("PROCESSED".equals(message.status())) return codec.read(message.resultJson());

    ObjectNode semantic = ((ObjectNode) root).deepCopy();
    semantic.remove("requestId");
    long canonicalId = repository.associate(flow, event, message.id(), codec.canonicalHash(semantic));
    var canonical = messages.findById(canonicalId);
    // 同一序号的同一通知即使换了requestId重发，也不能再次执行审批或退回。
    if ("PROCESSED".equals(canonical.status())) {
      repository.complete(message.id(), canonical.resultJson());
      return codec.read(canonical.resultJson());
    }
    if (event.version() != flow.appliedVersion() + 1) {
      throw conflict("SEQUENCE_CONFLICT", "通知序号不连续，当前应发送workflowState.version=" + (flow.appliedVersion() + 1));
    }
    if (OaWorkflowNotification.CLOSED.contains(flow.state())) {
      throw conflict("FLOW_CLOSED", "流程已经结束，不能接收新的办理通知");
    }
    if (event.formVersion() != document.sourceVersion()) {
      throw conflict("FORM_VERSION_MISMATCH", "通知的formVersion与已保存需求不一致；I01一次性接入的需求填写1");
    }

    handler.apply(message, event, document.id());
    repository.applied(flow, event);
    String reply = codec.write(OaWorkflowReply.success());
    repository.complete(canonicalId, reply);
    if (canonicalId != message.id()) repository.complete(message.id(), reply);
    return codec.read(reply);
  }

  private static OaIntegrationException conflict(String code, String message) {
    return OaIntegrationException.conflict(code, message);
  }
}
