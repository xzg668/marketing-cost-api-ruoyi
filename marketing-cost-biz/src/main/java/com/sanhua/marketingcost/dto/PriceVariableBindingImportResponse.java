package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 导入响应 —— {@code inserted}/{@code updated}/{@code expired}/{@code errors} 汇总统计。
 *
 * <p>{@code errors} 列表承载"部分失败"的明细：CSV 某行 token 不识别 / 联动行未找到 / factor 未登记 /
 * effective_date 解析失败等，成功行照常入库，不回滚。
 */
public class PriceVariableBindingImportResponse {

  /** 本次导入总行数 */
  private int total;
  /** 新插入的 binding 行数 */
  private int inserted;
  /** 原地更新的行数（同 key+effectiveDate） */
  private int updated;
  /** 版本切换时把旧行 expiry_date 设置的次数 */
  private int expired;
  /** 错误明细 */
  private final List<ErrorRow> errors = new ArrayList<>();

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public int getInserted() {
    return inserted;
  }

  public void setInserted(int inserted) {
    this.inserted = inserted;
  }

  public int getUpdated() {
    return updated;
  }

  public void setUpdated(int updated) {
    this.updated = updated;
  }

  public int getExpired() {
    return expired;
  }

  public void setExpired(int expired) {
    this.expired = expired;
  }

  public List<ErrorRow> getErrors() {
    return errors;
  }

  public void addError(int line, String materialCode, String specModel, String reason) {
    ErrorRow r = new ErrorRow();
    r.line = line;
    r.materialCode = materialCode;
    r.specModel = specModel;
    r.reason = reason;
    errors.add(r);
  }

  /** 单行错误 —— {@code line} 对应 CSV 物理行号（首行=1） */
  public static class ErrorRow {
    private int line;
    private String materialCode;
    private String specModel;
    private String reason;

    public int getLine() {
      return line;
    }

    public void setLine(int line) {
      this.line = line;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getSpecModel() {
      return specModel;
    }

    public void setSpecModel(String specModel) {
      this.specModel = specModel;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}
