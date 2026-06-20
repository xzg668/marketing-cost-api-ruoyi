package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteCostRunSummaryResponse {
  private Long id;
  private String costRunNo;
  private String versionNo;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String pricingMonth;
  private String resultPeriod;
  private String bomConfirmNo;
  private String priceTypeConfirmNo;
  private String pricePrepareNo;
  private String status;
  private BigDecimal totalCost;
  private Integer partItemCount;
  private Integer costItemCount;
  private LocalDateTime trialStartedAt;
  private LocalDateTime trialFinishedAt;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmMessage;
}
