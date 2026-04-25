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
  private int linkedCount;
  private int fixedCount;
  private int skipped;
  private List<ErrorRow> errors = new ArrayList<>();

  public String getBatchId() {
    return batchId;
  }

  public void setBatchId(String batchId) {
    this.batchId = batchId;
  }

  public int getLinkedCount() {
    return linkedCount;
  }

  public void setLinkedCount(int linkedCount) {
    this.linkedCount = linkedCount;
  }

  public int getFixedCount() {
    return fixedCount;
  }

  public void setFixedCount(int fixedCount) {
    this.fixedCount = fixedCount;
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
}
