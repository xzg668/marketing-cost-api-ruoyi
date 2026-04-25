package com.sanhua.marketingcost.dto;

/**
 * 联动公式预览请求体 —— 供前端编辑器实时预览用。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code formulaExpr}：Excel 原样公式（可含中文变量/全角括号/单位注释），必填</li>
 *   <li>{@code materialCode}：部品物料代码；可选，缺失时 PART_CONTEXT 派生会按 0 处理</li>
 *   <li>{@code pricingMonth}：计价月（{@code yyyy-MM}）；缺失时 finance 表按最新月回退</li>
 *   <li>{@code taxIncluded}：公式结果的含税口径标记，1=含税（默认，保持公式原值），
 *       0=不含税结算（公式按含税计算完成后，再除以 1+vat_rate 得到不含税结算价）；
 *       null 视同 1。对应 {@code lp_price_linked_item.tax_included} 列</li>
 * </ul>
 *
 * <p>前端输入示例：
 * <pre>
 *   {"formulaExpr": "下料重量*材料含税价格+加工费",
 *    "materialCode": "C3604A科宇",
 *    "pricingMonth": "2026-04",
 *    "taxIncluded": 0}
 * </pre>
 */
public class PriceLinkedFormulaPreviewRequest {

  /** Excel 原样公式（中文/英文/混排均可），必填 */
  private String formulaExpr;

  /** 部品物料代码，可选 */
  private String materialCode;

  /** 计价月 yyyy-MM，可选 */
  private String pricingMonth;

  /** 含税口径标记：1=含税（默认），0=不含税结算（触发 /(1+vat_rate) 转换），null 视同 1 */
  private Integer taxIncluded;

  public String getFormulaExpr() {
    return formulaExpr;
  }

  public void setFormulaExpr(String formulaExpr) {
    this.formulaExpr = formulaExpr;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public void setPricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
  }

  public Integer getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Integer taxIncluded) {
    this.taxIncluded = taxIncluded;
  }
}
