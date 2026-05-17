package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExcelAutoBindingImportLogDto {
  private Long id;
  private Long factorUploadBatchId;
  private Long linkedItemId;
  private String materialCode;
  private String supplierCode;
  private String tokenName;
  private String action;
  private String status;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String sourceWorkbookName;
  private String sourceSheetName;
  private String sourceCellRef;
  private String excelFormula;
  private String message;
  private LocalDateTime createdAt;
}
