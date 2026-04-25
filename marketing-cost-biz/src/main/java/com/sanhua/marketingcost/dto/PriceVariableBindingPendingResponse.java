package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * "待绑定联动行"列表响应 —— 供前端 {@code /price/linked/result} 顶部徽章 + 列表过滤使用。
 *
 * <p>{@code items} 承载具体行（物料+规格+公式），{@code total} 冗余一份方便徽章不遍历。
 */
public class PriceVariableBindingPendingResponse {

  private int total;
  private final List<PendingItem> items = new ArrayList<>();

  public int getTotal() {
    return total;
  }

  public void setTotal(int total) {
    this.total = total;
  }

  public List<PendingItem> getItems() {
    return items;
  }

  public void addItem(PendingItem item) {
    items.add(item);
    this.total = items.size();
  }

  public static class PendingItem {
    private Long linkedItemId;
    private String materialCode;
    private String specModel;
    private String formulaExpr;
    private String formulaExprCn;

    public Long getLinkedItemId() {
      return linkedItemId;
    }

    public void setLinkedItemId(Long linkedItemId) {
      this.linkedItemId = linkedItemId;
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

    public String getFormulaExpr() {
      return formulaExpr;
    }

    public void setFormulaExpr(String formulaExpr) {
      this.formulaExpr = formulaExpr;
    }

    public String getFormulaExprCn() {
      return formulaExprCn;
    }

    public void setFormulaExprCn(String formulaExprCn) {
      this.formulaExprCn = formulaExprCn;
    }
  }
}
