package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteIngestLogListItemResponse {
  private Long id;
  private String requestId;
  private String idempotencyKey;
  private String sourceType;
  private String sourceSystem;
  private String externalFormNo;
  private String oaNo;
  private String processCode;
  private String processName;
  private String quoteScenario;
  private String ingestStatus;
  private String classificationStatus;
  private String errorMessage;
  private LocalDateTime receivedAt;
  private LocalDateTime processedAt;
}
