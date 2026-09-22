package com.sanhua.marketingcost.dto.technicaldata;

import java.time.LocalDateTime;
import java.util.List;

public record TechnicalDataTaskSubmissionResponse(
    boolean submitted,
    boolean idempotentReplay,
    Long taskId,
    String taskNo,
    String taskStatus,
    String reviewStatus,
    Integer reviewRound,
    Integer taskVersion,
    String submissionFingerprint,
    LocalDateTime submittedAt,
    TechnicalDataTaskValidationResponse validation,
    List<ProductSubmission> products,
    Long submissionId,
    String deliveryStatus,
    boolean queued) {

  public TechnicalDataTaskSubmissionResponse {
    products = products == null ? List.of() : List.copyOf(products);
  }

  public record ProductSubmission(
      Long productId,
      String materialNo,
      Long submittedVersionId,
      Integer versionNo,
      String versionStatus,
      String contentFingerprint,
      int reviewItemCount) {}
}
