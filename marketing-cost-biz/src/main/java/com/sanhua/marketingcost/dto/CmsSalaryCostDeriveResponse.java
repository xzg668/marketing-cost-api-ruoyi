package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsSalaryCostDeriveResponse {
  private Long importBatchId;
  private int salaryInsertCount;
  private int salarySkipCount;
  private int salaryBlockedCount;
  private int errorCount;
  private String errorMessage;
}
