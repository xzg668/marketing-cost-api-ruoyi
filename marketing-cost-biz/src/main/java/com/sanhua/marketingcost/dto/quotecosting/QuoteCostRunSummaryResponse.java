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
  private String pricePrepareNo;
  private String oaPricePrepareNo;
  private String financePricePrepareNo;
  private BigDecimal financeCuPrice;
  private BigDecimal oaCuPrice;
  private BigDecimal financeCuPricePerTon;
  private BigDecimal oaCuPricePerTon;
  private Long financeBasePriceId;
  private String status;
  private String sourceRevision;
  private String dataQualityStatus;
  private Integer dataQualityWarningCount;
  private String dataQualitySummary;
  private BigDecimal totalCost;
  private BigDecimal financeBaseTotalCost;
  private BigDecimal financeMaterialCost;
  private BigDecimal oaMaterialCost;
  private BigDecimal cuMaterialAdjustment;
  private BigDecimal finalQuoteAmount;
  private Integer partItemCount;
  private Integer costItemCount;
  private LocalDateTime trialStartedAt;
  private LocalDateTime trialFinishedAt;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmMessage;
}
