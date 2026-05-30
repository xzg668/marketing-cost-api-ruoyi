package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 日常 OA 新旧链路双跑对账报告。 */
@Getter
@Setter
public class CostRunRegressionCompareReport {

  private String oaNo;
  private String productCode;
  private boolean matched = true;
  private int baselinePartCount;
  private int candidatePartCount;
  private int baselineCostItemCount;
  private int candidateCostItemCount;
  private List<CostRunRegressionDifference> differences = new ArrayList<>();

  public static CostRunRegressionCompareReport of(String oaNo, String productCode) {
    CostRunRegressionCompareReport report = new CostRunRegressionCompareReport();
    report.setOaNo(oaNo);
    report.setProductCode(productCode);
    return report;
  }

  public void addDifference(CostRunRegressionDifference difference) {
    if (difference == null) {
      return;
    }
    this.matched = false;
    this.differences.add(difference);
  }
}
