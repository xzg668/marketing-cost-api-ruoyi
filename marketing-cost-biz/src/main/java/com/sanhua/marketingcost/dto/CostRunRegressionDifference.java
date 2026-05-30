package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 日常 OA 新旧链路对账差异。 */
@Getter
@Setter
public class CostRunRegressionDifference {

  private String section;
  private String itemKey;
  private String fieldName;
  private String baselineValue;
  private String candidateValue;
  private String message;

  public static CostRunRegressionDifference of(
      String section,
      String itemKey,
      String fieldName,
      String baselineValue,
      String candidateValue,
      String message) {
    CostRunRegressionDifference difference = new CostRunRegressionDifference();
    difference.setSection(section);
    difference.setItemKey(itemKey);
    difference.setFieldName(fieldName);
    difference.setBaselineValue(baselineValue);
    difference.setCandidateValue(candidateValue);
    difference.setMessage(message);
    return difference;
  }
}
