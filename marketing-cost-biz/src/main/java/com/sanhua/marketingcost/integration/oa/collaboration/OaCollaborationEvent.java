package com.sanhua.marketingcost.integration.oa.collaboration;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

@JsonPropertyOrder({
    "eventId", "eventType", "eventVersion", "occurredAt", "sourceSystem",
    "traceId", "idempotencyKey", "data"
})
public record OaCollaborationEvent(
    String eventId,
    OaCollaborationEventType eventType,
    String eventVersion,
    OffsetDateTime occurredAt,
    String sourceSystem,
    String traceId,
    String idempotencyKey,
    JsonNode data) {}
