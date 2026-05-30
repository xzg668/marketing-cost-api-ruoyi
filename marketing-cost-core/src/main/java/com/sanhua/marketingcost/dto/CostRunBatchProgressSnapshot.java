package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 通用成本核算批次进度快照。 */
@Getter
@Setter
public class CostRunBatchProgressSnapshot {

  private String batchNo;
  private String status;
  private int totalCount;
  private int successCount;
  private int failedCount;
  private int skippedCount;
  private int runningCount;
  private int retryableCount;
  private int pendingCount;
  private int progress;
}
