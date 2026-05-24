package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceFixedItem;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定采购价 / 结算固定价导入响应。
 *
 * <p>导入允许“部分成功”：单行缺必填或价格列是备注时，通过 errors / warnings 告知前端，
 * 不再只返回入库记录列表。
 */
public class PriceFixedItemImportResponse {
  private int createdCount;
  private int updatedCount;
  private int skippedCount;
  private List<RowMessage> errors = new ArrayList<>();
  private List<RowMessage> warnings = new ArrayList<>();
  private List<PriceFixedItem> items = new ArrayList<>();

  public int getCreatedCount() {
    return createdCount;
  }

  public void setCreatedCount(int createdCount) {
    this.createdCount = createdCount;
  }

  public int getUpdatedCount() {
    return updatedCount;
  }

  public void setUpdatedCount(int updatedCount) {
    this.updatedCount = updatedCount;
  }

  public int getSkippedCount() {
    return skippedCount;
  }

  public void setSkippedCount(int skippedCount) {
    this.skippedCount = skippedCount;
  }

  public List<RowMessage> getErrors() {
    return errors;
  }

  public void setErrors(List<RowMessage> errors) {
    this.errors = errors == null ? new ArrayList<>() : errors;
  }

  public List<RowMessage> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<RowMessage> warnings) {
    this.warnings = warnings == null ? new ArrayList<>() : warnings;
  }

  public List<PriceFixedItem> getItems() {
    return items;
  }

  public void setItems(List<PriceFixedItem> items) {
    this.items = items == null ? new ArrayList<>() : items;
  }

  public void incrementCreatedCount() {
    this.createdCount++;
  }

  public void incrementUpdatedCount() {
    this.updatedCount++;
  }

  public void incrementSkippedCount() {
    this.skippedCount++;
  }

  public void addItem(PriceFixedItem item) {
    if (item != null) {
      this.items.add(item);
    }
  }

  public void addError(Integer rowNo, String materialCode, String message) {
    this.errors.add(new RowMessage(rowNo, materialCode, message));
  }

  public void addWarning(Integer rowNo, String materialCode, String message) {
    this.warnings.add(new RowMessage(rowNo, materialCode, message));
  }

  public static class RowMessage {
    private Integer rowNo;
    private String materialCode;
    private String message;

    public RowMessage() {
    }

    public RowMessage(Integer rowNo, String materialCode, String message) {
      this.rowNo = rowNo;
      this.materialCode = materialCode;
      this.message = message;
    }

    public Integer getRowNo() {
      return rowNo;
    }

    public void setRowNo(Integer rowNo) {
      this.rowNo = rowNo;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }
}
