package com.sanhua.marketingcost.dto.collaboration;

public record QuoteCollaborationStartResponse(
    String resultAction,
    boolean replay,
    String message,
    QuoteItemCollaborationResponse item) {}
