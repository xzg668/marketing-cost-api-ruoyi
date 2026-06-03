package com.sanhua.marketingcost.dto.packagecomponent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentBulkGenerateRequest {
  private String oaNo;
  private String topProductCode;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
  private LocalDate asOfDate;
  private LocalDateTime priceAsOfTime;
  private boolean forceRefresh = true;
}
