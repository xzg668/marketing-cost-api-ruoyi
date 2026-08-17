package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sanhua.marketingcost.config.OaCollaborationProperties;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.integration.oa.collaboration.CollaborationEventPayloadPolicy;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationEventType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-04 协作事件Outbox服务")
class CollaborationEventServiceTest {

  private IntegrationOutboxRepository repository;
  private CollaborationEventService service;

  @BeforeEach
  void setUp() {
    repository = mock(IntegrationOutboxRepository.class);
    ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    when(repository.saveOrGet(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service = new CollaborationEventService(
        repository,
        new OaCollaborationProperties(),
        objectMapper,
        new CollaborationIdempotency(),
        new CollaborationEventPayloadPolicy(objectMapper));
  }

  @Test
  @DisplayName("DISABLED事件固定以HOLD策略和HOLD状态写入")
  void appendsHoldEventWithoutExternalResult() throws Exception {
    CollaborationEventService.AppendResult result = service.append(command(
        OaCollaborationEventType.TECH_TASK_CREATED,
        JsonNodeFactory.instance.objectNode().put("productTaskNo", "QCPT-1")));

    IntegrationOutbox event = result.event();
    assertThat(result.replay()).isFalse();
    assertThat(event.getDestination()).isEqualTo("OA");
    assertThat(event.getSendPolicy()).isEqualTo("HOLD");
    assertThat(event.getSendStatus()).isEqualTo("HOLD");
    assertThat(event.getRetryCount()).isZero();
    assertThat(event.getNextRetryAt()).isNull();
    assertThat(event.getLastHttpStatus()).isNull();
    assertThat(event.getResponseJson()).isNull();
    assertThat(event.getSentAt()).isNull();
    assertThat(new ObjectMapper().readTree(event.getPayloadJson()).path("eventType").asText())
        .isEqualTo("TECH_TASK_CREATED");
  }

  @Test
  @DisplayName("同一幂等键同内容返回原事件，不同内容返回IDEMPOTENCY_CONFLICT")
  void replaysSamePayloadAndRejectsDifferentPayload() {
    CollaborationEventCommand firstCommand = command(
        OaCollaborationEventType.TECH_TASK_UPDATED,
        JsonNodeFactory.instance.objectNode().put("statusCode", "BOM_IN_PROGRESS"));
    IntegrationOutbox persisted = service.append(firstCommand).event();
    when(repository.saveOrGet(any())).thenReturn(persisted);

    assertThat(service.append(firstCommand).replay()).isTrue();
    CollaborationEventCommand changed = command(
        OaCollaborationEventType.TECH_TASK_UPDATED,
        JsonNodeFactory.instance.objectNode().put("statusCode", "PRICE_IN_PROGRESS"));
    assertThatThrownBy(() -> service.append(changed))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT));
  }

  @Test
  @DisplayName("敏感字段在入Outbox前即被拒绝")
  void rejectsUnsafePayloadBeforePersistence() {
    var data = JsonNodeFactory.instance.objectNode().put("formulaExpression", "铜价*净重");
    assertThatThrownBy(() -> service.append(command(
        OaCollaborationEventType.TECH_TASK_UPDATED, data)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CollaborationEventCommand command(
      OaCollaborationEventType eventType, com.fasterxml.jackson.databind.JsonNode data) {
    return new CollaborationEventCommand(
        "PRODUCT_TASK", 10L, "QCPT-20260813-001", 2, eventType, null,
        "trace-001", OffsetDateTime.parse("2026-08-13T10:15:30+08:00"), data);
  }
}
