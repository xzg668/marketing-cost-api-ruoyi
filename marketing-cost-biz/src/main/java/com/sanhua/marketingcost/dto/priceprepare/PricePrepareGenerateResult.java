package com.sanhua.marketingcost.dto.priceprepare;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGenerateResult {
  /** Actionable input gaps survive an enclosing repricing transaction rollback. */
  public record Issue(String materialCode,String materialName,String reasonCode,String message) {}
  private String prepareNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
  private String scenarioType;
  private String scenarioGroupNo;
  private String sourcePrepareNo;
  private String status;
  private int totalCount;
  private int successCount;
  private int warningCount;
  private int gapCount;
  private LocalDateTime priceAsOfTime;
  private String priceAsOfSource;
  private String message;
  private java.util.List<Issue> issues = java.util.List.of();
}
