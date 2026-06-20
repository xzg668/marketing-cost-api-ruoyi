package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
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
  private QuoteBomConfirmationSummaryResponse latestBomConfirmation;
  private QuotePriceTypeConfirmationSummaryResponse latestPriceTypeConfirmation;
  private QuotePricePrepareSummaryResponse latestPricePrepare;
  private QuoteCostRunSummaryResponse latestCostRun;
  private List<QuoteCostingWorkbenchBomRowResponse> bomRows = new ArrayList<>();
  private List<QuoteCostingWorkbenchTabResponse> tabs = new ArrayList<>();
}
