package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDateTime;
import java.util.List;

public final class CollaborationOperationsResponse {
  private CollaborationOperationsResponse() {}

  public record Reconciliation(int total, List<Issue> issues) {}
  public record Issue(String type, String severity, Long targetId, String taskNo,
      String oaNo, Long oaFormItemId, String message) {}
  public record OutboxPage(int total, List<OutboxItem> items) {}
  public record OutboxItem(Long id, String eventId, String aggregateType, Long aggregateId,
      Integer aggregateVersion, String eventType, String sendPolicy, String sendStatus,
      Integer retryCount, String lastErrorMessage, LocalDateTime occurredAt) {}
  public record PublicationFailures(int total, List<PublicationFailure> items) {}
  public record PublicationFailure(Long reviewId, String reviewNo, Long collaborationId,
      String oaNo, String reviewStatus, String masterStatus, String publishBatchNo,
      LocalDateTime updatedAt) {}
  public record CompensationRequest(String requestId, String reason) {}
  public record CompensationResult(String requestId, String action, Long targetId,
      String beforeStatus, String afterStatus, boolean replay) {}
}
