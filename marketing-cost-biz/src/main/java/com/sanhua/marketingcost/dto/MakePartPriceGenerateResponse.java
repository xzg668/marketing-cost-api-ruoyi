package com.sanhua.marketingcost.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartPriceGenerateResponse {
  private String calcBatchId;
  private int parentCount;
  private int totalCount;
  private int rowCount;
  private int okCount;
  private int warningCount;
  private int errorCount;
  private Map<String, Integer> statusSummary = new LinkedHashMap<>();

  public MakePartPriceGenerateResponse() {
  }

  public MakePartPriceGenerateResponse(
      String calcBatchId,
      int parentCount,
      int rowCount,
      int okCount,
      int warningCount,
      int errorCount) {
    this.calcBatchId = calcBatchId;
    this.parentCount = parentCount;
    this.totalCount = rowCount;
    this.rowCount = rowCount;
    this.okCount = okCount;
    this.warningCount = warningCount;
    this.errorCount = errorCount;
  }
}
