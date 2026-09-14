package com.sanhua.marketingcost.integration.technicaldata;

import java.time.LocalDateTime;

public interface TechnicalDataOaGateway {
  PublishResult publish(PublishCommand command);

  record PublishCommand(
      Long taskId,
      String taskNo,
      String oaNo,
      String accountingMonth,
      Long assigneeUserId,
      String assigneeName,
      LocalDateTime dueAt,
      String accessUrl,
      String idempotencyKey) {}

  record PublishResult(String externalTaskId, String status) {}
}
