package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceListRowDto {
  private Long factorIdentityId;
  private String businessUnitType;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private Long factorMonthlyPriceId;
  private String priceMonth;
  private BigDecimal dailyEffectivePrice;
  private Integer taxIncluded;
  private String unit;
  private String sourceTag;
  private Long sourceUploadBatchId;
  private Long latestAdjustBatchId;
  private String latestAdjustBatchNo;
  private String latestAdjustUsageScope;
  private BigDecimal latestAdjustPrice;
  private BigDecimal latestAdjustOriginalPrice;
  private BigDecimal latestAdjustDelta;
  private BigDecimal latestAdjustChangeRate;
  private String latestAdjustStatus;
  private String latestAdjustedBy;
  private LocalDateTime latestAdjustedAt;
}
