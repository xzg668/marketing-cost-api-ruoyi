package com.sanhua.marketingcost.dto.technicaldata;

import java.time.Instant;

public record TechnicalDataAccessTicketExchangeResponse(
    String accessToken, Long taskId, Long userId, String purpose,
    Instant expiresAt, String entryPath) {}
