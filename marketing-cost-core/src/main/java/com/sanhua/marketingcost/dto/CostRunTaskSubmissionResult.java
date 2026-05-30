package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 通用成本核算任务提交结果。 */
@Getter
@Setter
public class CostRunTaskSubmissionResult {

  private String batchNo;
  private String scene;
  private String sourceNo;
  private String status;
  private int totalCount;
  private int taskCount;
  private int skippedCount;
  private boolean existingBatch;

  public static CostRunTaskSubmissionResult of(
      String batchNo,
      String scene,
      String sourceNo,
      String status,
      int totalCount,
      int taskCount,
      int skippedCount,
      boolean existingBatch) {
    CostRunTaskSubmissionResult result = new CostRunTaskSubmissionResult();
    result.setBatchNo(batchNo);
    result.setScene(scene);
    result.setSourceNo(sourceNo);
    result.setStatus(status);
    result.setTotalCount(totalCount);
    result.setTaskCount(taskCount);
    result.setSkippedCount(skippedCount);
    result.setExistingBatch(existingBatch);
    return result;
  }
}
