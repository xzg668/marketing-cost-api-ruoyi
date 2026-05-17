package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceDto {
  private Long id;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal price;
  private Integer taxIncluded;
  private Long sourceUploadBatchId;
  private Long latestAdjustBatchId;
  private String latestAdjustSourceType;
  private String latestAdjustedBy;
  private LocalDateTime latestAdjustedAt;
  private String sourceTag;
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
