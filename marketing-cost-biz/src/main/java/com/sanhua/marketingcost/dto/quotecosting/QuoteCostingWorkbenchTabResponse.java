package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuoteCostingWorkbenchTabResponse {
  private String code;
  private String name;
  private String status;
  private String blockedReason;
}
