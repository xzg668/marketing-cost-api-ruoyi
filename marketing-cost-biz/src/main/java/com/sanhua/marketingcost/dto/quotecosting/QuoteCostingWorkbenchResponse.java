package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteCostingWorkspaceResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class QuoteCostingWorkbenchResponse {
  private QuoteCostingWorkbenchHeaderResponse header;
  private QuoteCostingWorkbenchItemResponse item;
  private QuoteBomStatusItemResponse bomStatus;
  private String periodMonth;
  private QuoteCostingWorkflowStatusResponse workflowStatus;
  private Boolean snapshotGenerated;
  private String buildBatchId;
  private QuoteCostingWorkspaceResponse costingWorkspace;
  private QuotePriceTypeRecognitionSummaryResponse latestPriceTypeRecognition;
  private QuotePricePrepareSummaryResponse latestPricePrepare;
  private QuoteCostRunSummaryResponse latestCostRun;
  private List<QuoteCostingWorkbenchBomRowResponse> bomRows = new ArrayList<>();
  private List<QuoteCostingWorkbenchTabResponse> tabs = new ArrayList<>();
}
