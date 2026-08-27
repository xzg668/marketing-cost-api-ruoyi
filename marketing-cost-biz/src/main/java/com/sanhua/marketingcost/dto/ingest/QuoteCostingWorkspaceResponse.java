package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;
import lombok.Data;

/** 详情页使用的轻量核算状态；不包含 BOM 行和价格明细。 */
@Data
public class QuoteCostingWorkspaceResponse {
  private String productCode;
  private String periodMonth;
  private String workspaceStatus;
  private String currentStep;
  private Boolean inputChanged;
  private String attentionCode;
  private Integer gapCount;
  private Integer carriedForwardPriceCount;
  private String staleReasonCode;
  private String lastErrorStep;
  private String lastErrorCode;
  private String lastErrorMessage;
  private String currentBomBuildBatchId;
  private String currentPrepareNo;
  private Long currentCostVersionId;
  private Long lastTaskId;
  private Integer lockVersion;
  private LocalDateTime lastCheckedAt;
}
