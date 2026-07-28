package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PriceLinkedImportBatchDetailDto {
  private FactorUploadBatchDto batch;
  private String batchId;
  private Long factorUploadBatchId;
  private String importPurpose;
  private String effectiveStrategy;
  private String formulaEffectiveDate;
  private String factorPriceConflictStrategy;
  private int factorRecognizedCount;
  private int monthlyPriceCreatedCount;
  private int monthlyPriceUpdatedCount;
  private int monthlyPriceUnchangedCount;
  private int monthlyPriceSkippedCount;
  private int monthlyPriceConflictCount;
  private int monthlyPriceOverwriteCount;
  private int quoteBaseRecognizedCount;
  private int quoteBaseUnrecognizedCount;
  private int quoteBaseConflictCount;
  private int linkedCount;
  private int linkedVersionCreatedCount;
  private int linkedUnchangedSkippedCount;
  private int linkedExpiredCount;
  private int autoBindingCount;
  private int conflictBindingCount;
  private int bindingErrorCount;
  private int errorCount;
  /** 旧批次只保存了失败数量、没有行级明细时，用于前端如实提示。 */
  private int unpersistedErrorCount;
  private int manualSkippedCount;
  private List<FactorMonthlyPriceUpsertResult.RowResult> factorRows = new ArrayList<>();
  private List<ExcelAutoBindingImportLogDto> bindingLogs = new ArrayList<>();
  private List<PriceItemImportResponse.BindingError> bindingErrors = new ArrayList<>();
  private List<PriceItemImportResponse.ErrorRow> errors = new ArrayList<>();
}
