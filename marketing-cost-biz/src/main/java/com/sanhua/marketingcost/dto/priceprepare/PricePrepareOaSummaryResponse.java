package com.sanhua.marketingcost.dto.priceprepare;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareOaSummaryResponse {
  private String oaNo;
  private int topProductCount;
  private int readyTopProductCount;
  private int totalCount;
  private int readyCount;
  private int gapCount;
  private String status;
  private LocalDateTime updatedAt;
}
