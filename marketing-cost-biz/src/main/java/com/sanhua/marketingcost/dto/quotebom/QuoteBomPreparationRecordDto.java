package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuoteBomPreparationRecordDto {
  private Long id;
  private Long oaFormItemId;
  private String oaNo;
  private String quoteProductCode;
  private String productType;
  private String bareProductCode;
  private Boolean needPackage;
  private String referenceFinishedCode;
  private String sourceTopProductCode;
  private String preparationStatus;
  private String reviewStatus;
  private Long taskId;
  private String reuseType;
  private LocalDate reuseValidUntil;
  private LocalDateTime reviewedAt;
}
