package com.sanhua.marketingcost.dto.priceprepare;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareCandidateResponse {
  private String oaNo;
  private String periodMonth;
  private String topProductCode;
  private String productName;
  private String productModel;
  private String customer;
  private LocalDate applyDate;
  private String calcStatus;
  private String ownerName;
  private String prepareStatus;
  private int totalCount;
  private int readyCount;
  private int gapCount;
  private LocalDateTime updatedAt;
}
