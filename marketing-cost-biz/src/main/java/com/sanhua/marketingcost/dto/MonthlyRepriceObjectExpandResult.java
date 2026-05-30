package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 月度调价核算对象展开结果。 */
@Getter
@Setter
public class MonthlyRepriceObjectExpandResult {

  private String repriceNo;
  private int totalCount;
  private int taskCount;
  private int skippedCount;

  public static MonthlyRepriceObjectExpandResult of(
      String repriceNo, int totalCount, int taskCount, int skippedCount) {
    MonthlyRepriceObjectExpandResult result = new MonthlyRepriceObjectExpandResult();
    result.setRepriceNo(repriceNo);
    result.setTotalCount(totalCount);
    result.setTaskCount(taskCount);
    result.setSkippedCount(skippedCount);
    return result;
  }
}
