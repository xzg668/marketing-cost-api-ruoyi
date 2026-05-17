package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceAdjustmentResponse {
  private Long factorMonthlyPriceId;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal oldPrice;
  private BigDecimal newPrice;
  private String changeType;
  private String changedBy;
  private String remark;
  private LocalDateTime changedAt;
}
