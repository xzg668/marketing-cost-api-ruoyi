package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteIngestLogDetailResponse {
  private Long id;
  private String requestId;
  private String idempotencyKey;
  private String payloadHash;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String oaNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private String ingestStatus;
  private String classificationStatus;
  private String payloadJson;
  private String normalizedJson;
  private String validationErrors;
  private String warningMessages;
  private String errorMessage;
  private LocalDateTime receivedAt;
  private LocalDateTime processedAt;
  private String createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
