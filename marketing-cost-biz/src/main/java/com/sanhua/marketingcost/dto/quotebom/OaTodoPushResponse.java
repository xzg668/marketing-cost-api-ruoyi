package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;

public record OaTodoPushResponse(
    Long taskId,
    String taskNo,
    String oaTodoId,
    String oaTodoUrl,
    String pushStatus,
    String pushErrorMessage,
    LocalDateTime lastPushAt) {}
