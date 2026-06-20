package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuoteCostingWorkflowStatusResponse {
  private String overallStatus;
  private String productDetailStatus;
  private String quoteBomStatus;
  private String priceTypeConfirmationStatus;
  private String pricePrepareStatus;
  private String costRunStatus;
  private String currentBlockedStep;
}
