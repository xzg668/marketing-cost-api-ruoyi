package com.sanhua.marketingcost.dto;

/**
 * BOM 导入单行错误项。
 *
 * <p>T3 原则：单行校验失败不中断整批，把错误收进 {@link BomImportResult#getErrors()}；
 * 整体成功也会返回（只是 errors 可能为空）。
 */
public class BomImportError {

  /** Excel 1-based 行号（对用户友好，可直接在 Excel 里定位） */
  private Integer rowIndex;

  /** 失败原因，中文短句 */
  private String reason;

  public BomImportError() {}

  public BomImportError(Integer rowIndex, String reason) {
    this.rowIndex = rowIndex;
    this.reason = reason;
  }

  public Integer getRowIndex() {
    return rowIndex;
  }

  public void setRowIndex(Integer rowIndex) {
    this.rowIndex = rowIndex;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
