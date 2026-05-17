package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteRequestIngestSummaryResponse {
  private Long id;
  private String requestId;
  private String idempotencyKey;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String ingestStatus;
  private String classificationStatus;
  private String payloadSummary;
  private String normalizedSummary;
  private String validationErrors;
  private String warningMessages;
  private String errorMessage;
  private LocalDateTime receivedAt;
  private LocalDateTime processedAt;
}
