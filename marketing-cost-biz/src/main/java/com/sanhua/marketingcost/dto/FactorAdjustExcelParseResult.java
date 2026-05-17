package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustExcelParseResult {
  private String sourceFileName;
  private String pricingMonth;
  private String businessUnitType;
  private int totalCount;
  private int matchedCount;
  private int failedCount;
  private int conflictCount;
  private List<FactorAdjustExcelParseRow> rows = new ArrayList<>();

  public void addRow(FactorAdjustExcelParseRow row) {
    rows.add(row);
    totalCount++;
    if (row == null || !"MATCHED".equalsIgnoreCase(row.getStatus())) {
      failedCount++;
      if (row != null && row.getFailReason() != null
          && row.getFailReason().contains("匹配到多条")) {
        conflictCount++;
      }
      return;
    }
    matchedCount++;
  }
}
