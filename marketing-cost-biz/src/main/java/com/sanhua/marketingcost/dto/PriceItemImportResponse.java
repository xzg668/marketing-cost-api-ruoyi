package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 联动/固定行 Excel 导入响应 —— T18 新增。
 *
 * <p>Controller 层总是 {@code CommonResult.success}，业务细节在本对象里：
 * <ul>
 *   <li>{@link #linkedCount} —— 入库到 {@code lp_price_linked_item} 的行数（insert + update）</li>
 *   <li>{@link #fixedCount} —— 入库到 {@code lp_price_fixed_item} 的行数</li>
 *   <li>{@link #skipped} —— 因校验失败或公式非法被跳过的行数</li>
 *   <li>{@link #errors} —— 跳过行的明细，前端按 {@code rowNumber} 定位到 Excel 原行</li>
 * </ul>
 */
public class PriceItemImportResponse {

  private String batchId;
  private Long factorUploadBatchId;
  private String importPurpose;
  private String effectiveStrategy;
  private int factorRecognizedCount;
  private int monthlyPriceCreatedCount;
  private int monthlyPriceUpdatedCount;
  private int monthlyPriceUnchangedCount;
  private int monthlyPriceSkippedCount;
  private int quoteBaseRecognizedCount;
  private int quoteBaseUnrecognizedCount;
  private int quoteBaseConflictCount;
  private int linkedCount;
  private int linkedCreatedCount;
  private int linkedUpdatedCount;
  private int linkedSkippedCount;
  private int fixedCount;
  /** 根据 Excel 单价列真实公式自动生成 / 更新的行局部变量绑定数。 */
  private int autoBindingCount;
  private int newHistoryBindingCount;
  private int consistentHistoryBindingCount;
  private int conflictBindingCount;
  private int manualSkippedCount;
  private int bindingErrorCount;
  private int skipped;
  private List<FactorMonthlyPriceUpsertResult.RowResult> factorRows = new ArrayList<>();
  private List<ErrorRow> errors = new ArrayList<>();
  private List<BindingError> bindingErrors = new ArrayList<>();

  public String getBatchId() {
    return batchId;
  }

  public void setBatchId(String batchId) {
    this.batchId = batchId;
  }

  public Long getFactorUploadBatchId() {
    return factorUploadBatchId;
  }

  public void setFactorUploadBatchId(Long factorUploadBatchId) {
    this.factorUploadBatchId = factorUploadBatchId;
  }

  public String getImportPurpose() {
    return importPurpose;
  }

  public void setImportPurpose(String importPurpose) {
    this.importPurpose = importPurpose;
  }

  public String getEffectiveStrategy() {
    return effectiveStrategy;
  }

  public void setEffectiveStrategy(String effectiveStrategy) {
    this.effectiveStrategy = effectiveStrategy;
  }

  public int getFactorRecognizedCount() {
    return factorRecognizedCount;
  }

  public void setFactorRecognizedCount(int factorRecognizedCount) {
    this.factorRecognizedCount = factorRecognizedCount;
  }

  public int getMonthlyPriceCreatedCount() {
    return monthlyPriceCreatedCount;
  }

  public void setMonthlyPriceCreatedCount(int monthlyPriceCreatedCount) {
    this.monthlyPriceCreatedCount = monthlyPriceCreatedCount;
  }

  public int getMonthlyPriceUpdatedCount() {
    return monthlyPriceUpdatedCount;
  }

  public void setMonthlyPriceUpdatedCount(int monthlyPriceUpdatedCount) {
    this.monthlyPriceUpdatedCount = monthlyPriceUpdatedCount;
  }

  public int getMonthlyPriceUnchangedCount() {
    return monthlyPriceUnchangedCount;
  }

  public void setMonthlyPriceUnchangedCount(int monthlyPriceUnchangedCount) {
    this.monthlyPriceUnchangedCount = monthlyPriceUnchangedCount;
  }

  public int getMonthlyPriceSkippedCount() {
    return monthlyPriceSkippedCount;
  }

  public void setMonthlyPriceSkippedCount(int monthlyPriceSkippedCount) {
    this.monthlyPriceSkippedCount = monthlyPriceSkippedCount;
  }

  public int getQuoteBaseRecognizedCount() {
    return quoteBaseRecognizedCount;
  }

  public void setQuoteBaseRecognizedCount(int quoteBaseRecognizedCount) {
    this.quoteBaseRecognizedCount = quoteBaseRecognizedCount;
  }

  public int getQuoteBaseUnrecognizedCount() {
    return quoteBaseUnrecognizedCount;
  }

  public void setQuoteBaseUnrecognizedCount(int quoteBaseUnrecognizedCount) {
    this.quoteBaseUnrecognizedCount = quoteBaseUnrecognizedCount;
  }

  public int getQuoteBaseConflictCount() {
    return quoteBaseConflictCount;
  }

  public void setQuoteBaseConflictCount(int quoteBaseConflictCount) {
    this.quoteBaseConflictCount = quoteBaseConflictCount;
  }

  public int getLinkedCount() {
    return linkedCount;
  }

  public void setLinkedCount(int linkedCount) {
    this.linkedCount = linkedCount;
  }

  public int getLinkedCreatedCount() {
    return linkedCreatedCount;
  }

  public void setLinkedCreatedCount(int linkedCreatedCount) {
    this.linkedCreatedCount = linkedCreatedCount;
  }

  public int getLinkedUpdatedCount() {
    return linkedUpdatedCount;
  }

  public void setLinkedUpdatedCount(int linkedUpdatedCount) {
    this.linkedUpdatedCount = linkedUpdatedCount;
  }

  public int getLinkedSkippedCount() {
    return linkedSkippedCount;
  }

  public void setLinkedSkippedCount(int linkedSkippedCount) {
    this.linkedSkippedCount = linkedSkippedCount;
  }

  public int getFixedCount() {
    return fixedCount;
  }

  public void setFixedCount(int fixedCount) {
    this.fixedCount = fixedCount;
  }

  public int getAutoBindingCount() {
    return autoBindingCount;
  }

  public void setAutoBindingCount(int autoBindingCount) {
    this.autoBindingCount = autoBindingCount;
  }

  public int getNewHistoryBindingCount() {
    return newHistoryBindingCount;
  }

  public void setNewHistoryBindingCount(int newHistoryBindingCount) {
    this.newHistoryBindingCount = newHistoryBindingCount;
  }

  public int getConsistentHistoryBindingCount() {
    return consistentHistoryBindingCount;
  }

  public void setConsistentHistoryBindingCount(int consistentHistoryBindingCount) {
    this.consistentHistoryBindingCount = consistentHistoryBindingCount;
  }

  public int getConflictBindingCount() {
    return conflictBindingCount;
  }

  public void setConflictBindingCount(int conflictBindingCount) {
    this.conflictBindingCount = conflictBindingCount;
  }

  public int getManualSkippedCount() {
    return manualSkippedCount;
  }

  public void setManualSkippedCount(int manualSkippedCount) {
    this.manualSkippedCount = manualSkippedCount;
  }

  public int getBindingErrorCount() {
    return bindingErrorCount;
  }

  public void setBindingErrorCount(int bindingErrorCount) {
    this.bindingErrorCount = bindingErrorCount;
  }

  public int getSkipped() {
    return skipped;
  }

  public void setSkipped(int skipped) {
    this.skipped = skipped;
  }

  public List<FactorMonthlyPriceUpsertResult.RowResult> getFactorRows() {
    return factorRows;
  }

  public void setFactorRows(List<FactorMonthlyPriceUpsertResult.RowResult> factorRows) {
    this.factorRows = factorRows;
  }

  public List<ErrorRow> getErrors() {
    return errors;
  }

  public void setErrors(List<ErrorRow> errors) {
    this.errors = errors;
  }

  public List<BindingError> getBindingErrors() {
    return bindingErrors;
  }

  public void setBindingErrors(List<BindingError> bindingErrors) {
    this.bindingErrors = bindingErrors;
  }

  /** Excel 单行错误信息。rowNumber 为 1-based 的 Excel 行号。 */
  public static class ErrorRow {
    private Integer rowNumber;
    private String materialCode;
    private String orderType;
    private String message;

    public ErrorRow() {
    }

    public ErrorRow(Integer rowNumber, String materialCode, String orderType, String message) {
      this.rowNumber = rowNumber;
      this.materialCode = materialCode;
      this.orderType = orderType;
      this.message = message;
    }

    public Integer getRowNumber() {
      return rowNumber;
    }

    public void setRowNumber(Integer rowNumber) {
      this.rowNumber = rowNumber;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getOrderType() {
      return orderType;
    }

    public void setOrderType(String orderType) {
      this.orderType = orderType;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }

  public static class BindingError {
    private Integer excelRowNumber;
    private String materialCode;
    private String tokenName;
    private String formula;
    private String refSheet;
    private Integer refRow;
    private Long existingFactorIdentity;
    private Long newFactorIdentity;
    private String reason;

    public BindingError() {
    }

    public Integer getExcelRowNumber() {
      return excelRowNumber;
    }

    public void setExcelRowNumber(Integer excelRowNumber) {
      this.excelRowNumber = excelRowNumber;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getTokenName() {
      return tokenName;
    }

    public void setTokenName(String tokenName) {
      this.tokenName = tokenName;
    }

    public String getFormula() {
      return formula;
    }

    public void setFormula(String formula) {
      this.formula = formula;
    }

    public String getRefSheet() {
      return refSheet;
    }

    public void setRefSheet(String refSheet) {
      this.refSheet = refSheet;
    }

    public Integer getRefRow() {
      return refRow;
    }

    public void setRefRow(Integer refRow) {
      this.refRow = refRow;
    }

    public Long getExistingFactorIdentity() {
      return existingFactorIdentity;
    }

    public void setExistingFactorIdentity(Long existingFactorIdentity) {
      this.existingFactorIdentity = existingFactorIdentity;
    }

    public Long getNewFactorIdentity() {
      return newFactorIdentity;
    }

    public void setNewFactorIdentity(Long newFactorIdentity) {
      this.newFactorIdentity = newFactorIdentity;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}
