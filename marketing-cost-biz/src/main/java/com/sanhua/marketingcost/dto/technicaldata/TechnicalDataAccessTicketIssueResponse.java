package com.sanhua.marketingcost.dto.technicaldata;

import java.time.Instant;

public record TechnicalDataAccessTicketIssueResponse(
    String ticket, Long taskId, Long userId, String purpose, Instant expiresAt) {}
