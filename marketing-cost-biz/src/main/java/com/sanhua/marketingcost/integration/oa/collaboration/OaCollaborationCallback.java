package com.sanhua.marketingcost.integration.oa.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record OaCollaborationCallback(
    String callbackId,
    OaCollaborationCallbackType callbackType,
    OffsetDateTime occurredAt,
    String sourceSystem,
    String traceId,
    String idempotencyKey,
    JsonNode data) {}
