package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 影响因素 Excel 导入响应 —— T17 新增。
 *
 * <p>接口 {@code POST /api/v1/base-prices/import-excel} 的返回类型。字段语义：
 * <ul>
 *   <li>{@link #batchId} —— 本次导入的 UUID，所有成功行的 {@code import_batch_id} 都是它</li>
 *   <li>{@link #imported} —— 成功入库（insert 或 update）的行数</li>
 *   <li>{@link #skipped} —— 因校验失败被跳过的行数（必填字段缺失 / 价格 ≤0 等）</li>
 *   <li>{@link #errors} —— 跳过原因明细，便于前端定位到具体 Excel 行号</li>
 * </ul>
 */
public class InfluenceFactorImportResponse {

  /** 本次导入批次 UUID；前端可存来做回滚按钮。 */
  private String batchId;

  /** 成功入库行数。 */
  private int imported;

  /** 跳过行数（与 {@link #errors} 大小一致）。 */
  private int skipped;

  /** 跳过原因明细。 */
  private List<ErrorRow> errors = new ArrayList<>();

  public String getBatchId() {
    return batchId;
  }

  public void setBatchId(String batchId) {
    this.batchId = batchId;
  }

  public int getImported() {
    return imported;
  }

  public void setImported(int imported) {
    this.imported = imported;
  }

  public int getSkipped() {
    return skipped;
  }

  public void setSkipped(int skipped) {
    this.skipped = skipped;
  }

  public List<ErrorRow> getErrors() {
    return errors;
  }

  public void setErrors(List<ErrorRow> errors) {
    this.errors = errors;
  }

  /**
   * 单条错误行 —— {@link #rowNumber} 是 Excel 里 1-based 行号（含标题/表头行），
   * {@link #shortName} 帮定位到因素，{@link #message} 说明原因。
   */
  public static class ErrorRow {
    private Integer rowNumber;
    private String shortName;
    private String message;

    public ErrorRow() {
    }

    public ErrorRow(Integer rowNumber, String shortName, String message) {
      this.rowNumber = rowNumber;
      this.shortName = shortName;
      this.message = message;
    }

    public Integer getRowNumber() {
      return rowNumber;
    }

    public void setRowNumber(Integer rowNumber) {
      this.rowNumber = rowNumber;
    }

    public String getShortName() {
      return shortName;
    }

    public void setShortName(String shortName) {
      this.shortName = shortName;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }
}
