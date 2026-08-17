package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationEventType;
import java.time.OffsetDateTime;

public record CollaborationEventCommand(
    String aggregateType,
    Long aggregateId,
    String aggregateNo,
    Integer aggregateVersion,
    OaCollaborationEventType eventType,
    String target,
    String traceId,
    OffsetDateTime occurredAt,
    JsonNode data) {}
