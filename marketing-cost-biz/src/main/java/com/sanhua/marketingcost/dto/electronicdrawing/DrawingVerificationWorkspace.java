package com.sanhua.marketingcost.dto.electronicdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 报价员读模型；本地取图 ID 与上游字符串任务号分别命名。 */
public record DrawingVerificationWorkspace(
    String oaNo,
    Long itemId,
    String accountingMonth,
    Task current,
    List<Task> history,
    String workflowStage,
    Long sourceVersionId,
    List<DrawingOption> drawingOptions,
    String notice) {

  public record DrawingOption(String drawingNo, String materialNo, String name, String model) {}

  public record Task(
      Long localTaskId,
      String externalTaskId,
      String drawingNo,
      String taskState,
      String consumptionState,
      Integer differenceCount,
      String editUrl,
      String errorMessage,
      Long currentResultId,
      Long consumedResultId,
      LocalDateTime createdAt,
      List<Result> results) {}

  public record Result(
      Long resultId,
      String eventType,
      Integer revision,
      Long confirmedAt,
      LocalDateTime receivedAt) {}

  /** summary 和 PLM 保留所有扩展字段；plmSourceResultId 指向同任务的首次来源。 */
  public record Detail(
      Long localTaskId,
      Long resultId,
      String eventType,
      String adoption,
      JsonNode summary,
      JsonNode data,
      JsonNode plmData,
      Long plmSourceResultId,
      List<Long> sourceVersionIds,
      LocalDateTime receivedAt) {}
}
