package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;

/**
 * BOM 批次列表摘要 —— 对应 {@code GET /api/v1/bom/batches} 返回的列表项。
 *
 * <p>语义随 {@code layer} 不同而变：
 * <ul>
 *   <li>{@code U9_SOURCE}：{@link #batchId} = import_batch_id；{@link #rowCount} = 该批次导入的 u9 行数</li>
 *   <li>{@code RAW_HIERARCHY}：batchId = build_batch_id；rowCount = 该批次展开的 raw 节点数</li>
 *   <li>{@code COSTING_ROW}：batchId = build_batch_id；rowCount = 拍平出的结算行数</li>
 * </ul>
 * T3 只实现 U9_SOURCE 层的查询；其他 layer 由 T4 / T5 补。
 */
public class BomBatchSummary {

  /** 批次 UUID */
  private String batchId;

  /** 数据源类型：EXCEL / U9_API / MANUAL */
  private String sourceType;

  /** Excel 文件名（RAW / COSTING 层可为空） */
  private String sourceFileName;

  /** 该批次的行数 */
  private long rowCount;

  /** 批次完成时间（U9_SOURCE 层 = imported_at） */
  private LocalDateTime importedAt;

  /** 操作人（U9_SOURCE 层 = imported_by） */
  private String importedBy;

  // ============================ getter / setter ============================

  public String getBatchId() {
    return batchId;
  }

  public void setBatchId(String batchId) {
    this.batchId = batchId;
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

  public long getRowCount() {
    return rowCount;
  }

  public void setRowCount(long rowCount) {
    this.rowCount = rowCount;
  }

  public LocalDateTime getImportedAt() {
    return importedAt;
  }

  public void setImportedAt(LocalDateTime importedAt) {
    this.importedAt = importedAt;
  }

  public String getImportedBy() {
    return importedBy;
  }

  public void setImportedBy(String importedBy) {
    this.importedBy = importedBy;
  }
}
