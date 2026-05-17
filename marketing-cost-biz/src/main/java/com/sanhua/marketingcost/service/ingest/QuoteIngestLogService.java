package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteNormalizedDocument;
import com.sanhua.marketingcost.entity.QuoteIngestLog;

public interface QuoteIngestLogService {
  QuoteIngestLog findByIdempotencyKey(String idempotencyKey);

  QuoteIngestLog createReceived(
      QuoteIngestRequest request,
      QuoteNormalizedDocument normalized,
      String requestId,
      String idempotencyKey,
      String payloadHash,
      String payloadJson,
      String normalizedJson);

  void refreshReceived(
      QuoteIngestLog log,
      QuoteIngestRequest request,
      QuoteNormalizedDocument normalized,
      String requestId,
      String payloadHash,
      String payloadJson,
      String normalizedJson);

  void markRejected(QuoteIngestLog log, QuoteNormalizedDocument normalized, String message);

  void markImported(
      QuoteIngestLog log, QuoteNormalizedDocument normalized, Long oaFormId, String oaNo);
}
