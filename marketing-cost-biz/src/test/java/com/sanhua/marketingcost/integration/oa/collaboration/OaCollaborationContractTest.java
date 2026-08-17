package com.sanhua.marketingcost.integration.oa.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.config.OaCollaborationProperties;
import com.sanhua.marketingcost.integration.oa.DisabledOaTodoGateway;
import com.sanhua.marketingcost.integration.oa.OaTodoGateway;
import java.time.OffsetDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-04 OA关闭适配器与事件契约")
class OaCollaborationContractTest {

  private final ObjectMapper objectMapper = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .build();

  @Test
  @DisplayName("事件类型完整覆盖OA V0.5实际定义的十四类事件")
  void exposesEveryOaV05EventType() {
    assertThat(Arrays.stream(OaCollaborationEventType.values()).map(Enum::name))
        .containsExactly(
            "TECH_TASK_CREATED",
            "TECH_TASK_LINKED",
            "TECH_TASK_UPDATED",
            "TECH_TASK_COMPLETED",
            "FINANCE_REVIEW_READY",
            "TECH_TASK_REOPENED",
            "FINANCE_REVIEW_RESUMED",
            "FINANCE_REVIEW_PROCESSING",
            "FINANCE_REVIEW_COMPLETED",
            "APPROVED_RESULT_REUSED",
            "COSTING_STARTED",
            "COSTING_COMPLETED",
            "COLLABORATION_CANCELLED",
            "SYSTEM_SYNC_FAILED");
  }

  @Test
  @DisplayName("事件JSON根字段与OA V0.5通用结构完全一致")
  void serializesCommonEventEnvelope() throws Exception {
    var data = JsonNodeFactory.instance.objectNode()
        .put("productTaskNo", "QCPT-20260813-001")
        .put("taskVersion", 3)
        .put("statusCode", "BOM_IN_PROGRESS");
    OaCollaborationEvent event = new OaCollaborationEvent(
        "0f79fc55-aeb4-4eef-9629-c66e70e3e953",
        OaCollaborationEventType.TECH_TASK_UPDATED,
        "1.0",
        OffsetDateTime.parse("2026-08-13T10:15:30+08:00"),
        "QUOTE_COST",
        "trace-001",
        "QCPT-20260813-001:3:TECH_TASK_UPDATED",
        data);

    var json = objectMapper.valueToTree(event);
    assertThat(json.fieldNames()).toIterable().containsExactly(
        "eventId", "eventType", "eventVersion", "occurredAt", "sourceSystem",
        "traceId", "idempotencyKey", "data");
    assertThat(json.path("eventType").asText()).isEqualTo("TECH_TASK_UPDATED");
    assertThat(json.path("data").path("taskVersion").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("DISABLED模式不调用外部系统且不伪造流程号或待办号")
  void disabledGatewaysNeverCreateExternalIdentifiers() {
    OaCollaborationProperties properties = new OaCollaborationProperties();
    assertThat(properties.getMode()).isEqualTo(OaCollaborationMode.DISABLED);
    DisabledOaCollaborationGateway gateway = new DisabledOaCollaborationGateway(properties);
    var event = new OaCollaborationEvent(
        "event-1", OaCollaborationEventType.TECH_TASK_CREATED, "1.0",
        OffsetDateTime.now(), "QUOTE_COST", "trace-1", "key-1",
        JsonNodeFactory.instance.objectNode().put("productTaskNo", "QCPT-1"));

    OaCollaborationDispatchReceipt receipt = gateway.dispatch(event);
    assertThat(receipt.status()).isEqualTo(OaCollaborationDispatchStatus.DEFERRED);
    assertThat(receipt.code()).isEqualTo("INTEGRATION_DISABLED");
    assertThat(receipt.oaProcessInstanceId()).isNull();
    assertThat(receipt.oaExternalTaskId()).isNull();

    OaTodoGateway legacyGateway = new DisabledOaTodoGateway();
    OaTodoGateway.PushResult legacy = legacyGateway.push(
        new OaTodoGateway.PushRequest(1L, "OLD-1", "标题", "王工", "/task/1", "{}"));
    assertThat(legacy.success()).isFalse();
    assertThat(legacy.oaTodoId()).isNull();
    assertThat(legacy.oaTodoUrl()).isNull();
    assertThat(legacy.errorMessage()).isEqualTo("INTEGRATION_DISABLED");
  }

  @Test
  @DisplayName("禁用回调对重复报文和错误签名统一拒绝且不委托业务处理")
  void disabledCallbackAlwaysReturnsIntegrationDisabled() {
    DisabledOaCollaborationCallbackPort port = new DisabledOaCollaborationCallbackPort();
    var callback = new OaCollaborationCallback(
        "callback-1", OaCollaborationCallbackType.OA_TASK_ASSIGNEE_CHANGED,
        OffsetDateTime.now(), "OA", "trace-1", "OA-TASK-1:2:ASSIGNEE_CHANGED",
        JsonNodeFactory.instance.objectNode().put("productTaskNo", "QCPT-1"));

    OaCollaborationCallbackReceipt first = port.handle(callback, "bad-signature");
    OaCollaborationCallbackReceipt repeated = port.handle(callback, "bad-signature");
    assertThat(first.accepted()).isFalse();
    assertThat(first.code()).isEqualTo("INTEGRATION_DISABLED");
    assertThat(repeated).isEqualTo(first);
  }

  @Test
  @DisplayName("OA摘要拒绝完整公式、票据、金额、完整BOM树和跨产品清单")
  void rejectsSensitiveOrOutOfScopePayloads() {
    CollaborationEventPayloadPolicy policy = new CollaborationEventPayloadPolicy(objectMapper);
    var safe = JsonNodeFactory.instance.objectNode()
        .put("productTaskNo", "QCPT-1")
        .put("productCode", "1008900001289")
        .put("statusCode", "WAIT_TECH")
        .put("openGapCount", 4);
    policy.requireSafe(safe);

    for (String forbidden : new String[]{
        "formulaExpression", "formulaText", "ssoTicket", "accessToken", "unitPrice",
        "bomTree", "priceDraftFields", "products", "quotedProductCodes"}) {
      var unsafe = JsonNodeFactory.instance.objectNode();
      unsafe.set("summary", JsonNodeFactory.instance.objectNode().put(forbidden, "secret"));
      assertThatThrownBy(() -> policy.requireSafe(unsafe))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(forbidden);
    }
  }
}
