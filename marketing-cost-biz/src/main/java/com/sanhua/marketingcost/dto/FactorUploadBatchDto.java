package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorUploadBatchDto {
  private Long id;
  private String batchNo;
  private String importType;
  private String importPurpose;
  private String effectiveStrategy;
  private String priceMonth;
  private String businessUnitType;
  private String fileName;
  private String uploadedBy;
  private String status;
  private Integer factorSheetCount;
  private Integer linkedSheetCount;
  private Integer factorRowCount;
  private Integer linkedRowCount;
  private Integer autoBindingCount;
  private Integer warningCount;
  private Integer errorCount;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
}
