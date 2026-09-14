package com.sanhua.marketingcost.dto.technicaldata;

import java.time.Instant;

public record TechnicalDataOaCallbackRequest(
    String eventId,
    Long taskId,
    String externalTaskId,
    String status,
    Long sequence,
    Instant occurredAt,
    Long timestamp,
    String signature) {}
