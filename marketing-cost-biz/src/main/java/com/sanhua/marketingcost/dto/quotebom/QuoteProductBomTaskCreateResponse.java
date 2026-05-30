package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDateTime;
import java.util.List;

public record QuoteProductBomTaskCreateResponse(
    int requestedCount,
    int createdTaskCount,
    int reusedTaskCount,
    int rejectedCount,
    List<TaskLink> tasks,
    List<String> rejectedMessages) {

  public record TaskLink(
      Long taskId,
      String taskNo,
      Long oaFormItemId,
      String oaNo,
      String quoteProductCode,
      String taskType,
      String taskStatus,
      String token,
      LocalDateTime tokenExpireTime,
      String collaborationUrl,
      boolean reused) {}
}
