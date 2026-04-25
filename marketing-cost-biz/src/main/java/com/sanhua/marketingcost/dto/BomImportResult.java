package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * BOM Excel 导入结果 —— 对应 {@code POST /api/v1/bom/import} 响应体。
 *
 * <p>字段语义（见 BOM 三层架构设计文档 §E.1）：
 * <ul>
 *   <li>{@link #importBatchId}：本次导入批次的 UUID，后续阶段 B Build 要拿这个值过滤 u9_source</li>
 *   <li>{@link #totalRows}：Excel 数据区域总行数（不含标题/表头）</li>
 *   <li>{@link #successRows}：成功落 DB 的行数（totalRows - errors.size()）</li>
 *   <li>{@link #errors}：校验失败或解析失败的单行错误明细，上限无，由 Service 控制</li>
 * </ul>
 */
public class BomImportResult {

  /** 批次 UUID，形如 {@code b_20260423_1a2b3c} */
  private String importBatchId;

  /** 数据源：EXCEL / U9_API（本期只有 EXCEL） */
  private String sourceType;

  /** Excel 原文件名 */
  private String sourceFileName;

  /** 总行数（数据区） */
  private int totalRows;

  /** 成功落库行数 */
  private int successRows;

  /** 单行错误明细 */
  private List<BomImportError> errors = new ArrayList<>();

  /** 导入完成时间 */
  private LocalDateTime importedAt;

  // ============================ getter / setter ============================

  public String getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(String importBatchId) {
    this.importBatchId = importBatchId;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public void setSourceFileName(String sourceFileName) {
    this.sourceFileName = sourceFileName;
  }

  public int getTotalRows() {
    return totalRows;
  }

  public void setTotalRows(int totalRows) {
    this.totalRows = totalRows;
  }

  public int getSuccessRows() {
    return successRows;
  }

  public void setSuccessRows(int successRows) {
    this.successRows = successRows;
  }

  public List<BomImportError> getErrors() {
    return errors;
  }

  public void setErrors(List<BomImportError> errors) {
    this.errors = errors;
  }

  public LocalDateTime getImportedAt() {
    return importedAt;
  }

  public void setImportedAt(LocalDateTime importedAt) {
    this.importedAt = importedAt;
  }
}
