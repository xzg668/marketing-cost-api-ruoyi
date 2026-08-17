package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.config.OaCollaborationProperties;
import com.sanhua.marketingcost.entity.IntegrationOutbox;
import com.sanhua.marketingcost.integration.oa.collaboration.CollaborationEventPayloadPolicy;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationEvent;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 协作状态事件的唯一Outbox写入入口。 */
@Service
public class CollaborationEventService {

  private static final String DESTINATION = "OA";
  private static final String SOURCE_SYSTEM = "QUOTE_COST";
  private static final String EVENT_VERSION = "1.0";
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final IntegrationOutboxRepository repository;
  private final OaCollaborationProperties properties;
  private final ObjectMapper objectMapper;
  private final CollaborationIdempotency idempotency;
  private final CollaborationEventPayloadPolicy payloadPolicy;

  public CollaborationEventService(
      IntegrationOutboxRepository repository,
      OaCollaborationProperties properties,
      ObjectMapper objectMapper,
      CollaborationIdempotency idempotency,
      CollaborationEventPayloadPolicy payloadPolicy) {
    this.repository = repository;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.idempotency = idempotency;
    this.payloadPolicy = payloadPolicy;
  }

  @Transactional
  public AppendResult append(CollaborationEventCommand command) {
    requireCommand(command);
    payloadPolicy.requireSafe(command.data());
    String idempotencyKey = CollaborationIdempotencyKeys.oaEvent(
        command.aggregateNo(), command.aggregateVersion(), command.eventType().name(),
        command.target());
    String payloadHash = idempotency.payloadHash(fingerprintJson(command));
    OffsetDateTime occurredAt = command.occurredAt() == null
        ? OffsetDateTime.now(BUSINESS_ZONE) : command.occurredAt();
    String eventId = UUID.randomUUID().toString();
    OaCollaborationEvent event = new OaCollaborationEvent(
        eventId,
        command.eventType(),
        EVENT_VERSION,
        occurredAt,
        SOURCE_SYSTEM,
        textOr(command.traceId(), UUID.randomUUID().toString()),
        idempotencyKey,
        command.data().deepCopy());

    IntegrationOutbox proposed = toOutbox(command, event, payloadHash);
    IntegrationOutbox persisted = repository.saveOrGet(proposed);
    boolean replay = !eventId.equals(persisted.getEventId());
    if (replay) {
      idempotency.check(persisted.getPayloadHash(), payloadHash);
    } else if (!payloadHash.equals(persisted.getPayloadHash())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
          "首次Outbox事件保存后的摘要不一致");
    }
    return new AppendResult(persisted, replay);
  }

  private IntegrationOutbox toOutbox(
      CollaborationEventCommand command,
      OaCollaborationEvent event,
      String payloadHash) {
    IntegrationOutbox outbox = new IntegrationOutbox();
    outbox.setEventId(event.eventId());
    outbox.setIdempotencyKey(event.idempotencyKey());
    outbox.setDestination(DESTINATION);
    outbox.setAggregateType(command.aggregateType().trim());
    outbox.setAggregateId(command.aggregateId());
    outbox.setAggregateVersion(command.aggregateVersion());
    outbox.setEventType(command.eventType().name());
    outbox.setEventVersion(EVENT_VERSION);
    try {
      outbox.setPayloadJson(objectMapper.writeValueAsString(event));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("OA事件无法序列化", exception);
    }
    outbox.setPayloadHash(payloadHash);
    if (properties.getMode() == OaCollaborationMode.DISABLED) {
      outbox.setSendPolicy("HOLD");
      outbox.setSendStatus("HOLD");
    } else {
      outbox.setSendPolicy("AUTO");
      outbox.setSendStatus("PENDING");
    }
    outbox.setRetryCount(0);
    outbox.setOccurredAt(event.occurredAt().toLocalDateTime());
    return outbox;
  }

  private String fingerprintJson(CollaborationEventCommand command) {
    ObjectNode fingerprint = objectMapper.createObjectNode();
    fingerprint.put("aggregateType", command.aggregateType().trim());
    fingerprint.put("aggregateId", command.aggregateId());
    fingerprint.put("aggregateNo", command.aggregateNo().trim());
    fingerprint.put("aggregateVersion", command.aggregateVersion());
    fingerprint.put("eventType", command.eventType().name());
    fingerprint.put("eventVersion", EVENT_VERSION);
    if (command.target() != null && !command.target().isBlank()) {
      fingerprint.put("target", command.target().trim());
    }
    fingerprint.set("data", command.data());
    return fingerprint.toString();
  }

  private static void requireCommand(CollaborationEventCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("协作事件命令不能为空");
    }
    CollaborationScope.requireText(command.aggregateType(), "聚合类型");
    if (command.aggregateId() == null || command.aggregateId() <= 0) {
      throw new IllegalArgumentException("聚合ID必须为正数");
    }
    CollaborationScope.requireText(command.aggregateNo(), "聚合编号");
    if (command.aggregateVersion() == null || command.aggregateVersion() <= 0) {
      throw new IllegalArgumentException("聚合版本必须为正数");
    }
    if (command.eventType() == null) {
      throw new IllegalArgumentException("事件类型不能为空");
    }
  }

  private static String textOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  public record AppendResult(IntegrationOutbox event, boolean replay) {}
}
