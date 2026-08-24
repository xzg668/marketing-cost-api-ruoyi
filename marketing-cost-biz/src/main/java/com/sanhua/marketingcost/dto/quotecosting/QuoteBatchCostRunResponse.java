package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

/** OA 整单核算批次的轻量进度。 */
@Data
public class QuoteBatchCostRunResponse {
  private String oaNo;
  private String periodMonth;
  private String batchNo;
  private String status;
  private String prerequisiteStatus;
  private String message;
  private int totalCount;
  private int queuedCount;
  private int runningCount;
  private int successCount;
  private int collaborationCount;
  private int failedCount;
  private int skippedCurrentCount;
  private int progress;
  private boolean active;
  private boolean existingBatch;
}
