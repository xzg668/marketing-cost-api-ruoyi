package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 公式回洗端点响应 —— Plan B T7。
 *
 * <p>管理员调 {@code POST /api/v1/admin/linked/formula-reformat} 后返回：
 * <ul>
 *   <li>{@link #total} —— 扫描到的 {@code lp_price_linked_item} 行总数</li>
 *   <li>{@link #rewrote} —— 本次实际回写（normalize 后与原值不同）的行数</li>
 *   <li>{@link #unchanged} —— 已规范化、无需回写的行数（幂等表征）</li>
 *   <li>{@link #failed} —— Normalizer 抛异常的行明细；不写回，由人工修正后再跑</li>
 * </ul>
 */
public class FormulaReformatResponse {

  private int total;
  private int rewrote;
  private int unchanged;
  /** 单独统计 cn 列派生回写的行数（expr 不变、仅 cn 需更新的场景也计入 rewroteCn） */
  private int rewroteCn;
  private List<FailedRow> failed = new ArrayList<>();

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public int getRewrote() {
    return rewrote;
  }

  public void setRewrote(int rewrote) {
    this.rewrote = rewrote;
  }

  public int getUnchanged() {
    return unchanged;
  }

  public void setUnchanged(int unchanged) {
    this.unchanged = unchanged;
  }

  public int getRewroteCn() {
    return rewroteCn;
  }

  public void setRewroteCn(int rewroteCn) {
    this.rewroteCn = rewroteCn;
  }

  public List<FailedRow> getFailed() {
    return failed;
  }

  public void setFailed(List<FailedRow> failed) {
    this.failed = failed;
  }

  /** 失败行明细 —— id + 原公式 + 失败原因（Normalizer 异常 message）。 */
  public static class FailedRow {
    private Long id;
    private String formulaExpr;
    private String reason;

    public FailedRow() {
    }

    public FailedRow(Long id, String formulaExpr, String reason) {
      this.id = id;
      this.formulaExpr = formulaExpr;
      this.reason = reason;
    }

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public String getFormulaExpr() {
      return formulaExpr;
    }

    public void setFormulaExpr(String formulaExpr) {
      this.formulaExpr = formulaExpr;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}
