package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 变量目录响应体 —— T15 新增。
 *
 * <p>接口 {@code GET /api/v1/price-linked/variables/catalog} 的返回类型，
 * 对应前端"变量选择"树形控件的三大分组。三组都对应 {@code lp_price_variable.factor_type}：
 * <ul>
 *   <li>{@link #financeFactors} —— {@code FINANCE_FACTOR}：财务影响因素（Cu/Zn/美国柜装黄铜 ...），
 *       附带最新一期基准价、单位、价期月，便于编辑器实时提示。</li>
 *   <li>{@link #partContexts} —— {@code PART_CONTEXT}：部品上下文（blank_weight/process_fee/
 *       material_price_incl ...），附带 {@code context_binding_json} 原文，
 *       前端据此判断字段来自实体字段还是派生策略。</li>
 *   <li>{@link #formulaRefs} —— {@code FORMULA_REF}：可复用的公式引用变量，附带公式表达式原文。</li>
 * </ul>
 *
 * <p>设计约定：
 * <ol>
 *   <li>只展示 {@code status='active'} 的变量，避免禁用态污染下拉。</li>
 *   <li>{@code CONST} 类型不列出 —— 前端不允许直接引用常量。</li>
 *   <li>三个列表永远是非 null 的 ArrayList（即使为空）；前端可安全地 {@code .map/.length}。</li>
 * </ol>
 */
public class VariableCatalogResponse {

  /** 财务影响因素分组 —— 来自 {@code lp_price_variable.factor_type='FINANCE_FACTOR'}。 */
  private List<FinanceFactor> financeFactors = new ArrayList<>();

  /** 部品上下文分组 —— 来自 {@code lp_price_variable.factor_type='PART_CONTEXT'}。 */
  private List<PartContext> partContexts = new ArrayList<>();

  /** 公式引用分组 —— 来自 {@code lp_price_variable.factor_type='FORMULA_REF'}。 */
  private List<FormulaRef> formulaRefs = new ArrayList<>();

  public List<FinanceFactor> getFinanceFactors() {
    return financeFactors;
  }

  public void setFinanceFactors(List<FinanceFactor> financeFactors) {
    this.financeFactors = financeFactors;
  }

  public List<PartContext> getPartContexts() {
    return partContexts;
  }

  public void setPartContexts(List<PartContext> partContexts) {
    this.partContexts = partContexts;
  }

  public List<FormulaRef> getFormulaRefs() {
    return formulaRefs;
  }

  public void setFormulaRefs(List<FormulaRef> formulaRefs) {
    this.formulaRefs = formulaRefs;
  }

  /**
   * 财务因素条目 —— 变量元信息 + 最新一期基准价快照。
   *
   * <p>{@link #currentPrice}/{@link #unit}/{@link #source}/{@link #pricingMonth}
   * 都来自 {@code lp_finance_base_price} 最近一条（{@code price_month desc, id desc}）。
   * 若表里查不到（例如因素刚建但未导入价格），这 4 个字段为 null，前端仅显示 {@link #code}/{@link #name}。
   */
  public static class FinanceFactor {
    /** 变量编码（公式里用的 token），如 "Cu"、"us_brass_price"。 */
    private String code;

    /** 变量中文名，与 {@code lp_finance_base_price.short_name} 对齐。 */
    private String name;

    /** 最新基准价，已含/未含税由 {@link #source} + 变量自身 tax_mode 决定。 */
    private BigDecimal currentPrice;

    /** 单位，如 "元/kg"。 */
    private String unit;

    /** 价格来源标签，来自 {@code lp_finance_base_price.price_source}。 */
    private String source;

    /** 价期月，如 "2024-03"。 */
    private String pricingMonth;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public BigDecimal getCurrentPrice() {
      return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
      this.currentPrice = currentPrice;
    }

    public String getUnit() {
      return unit;
    }

    public void setUnit(String unit) {
      this.unit = unit;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public String getPricingMonth() {
      return pricingMonth;
    }

    public void setPricingMonth(String pricingMonth) {
      this.pricingMonth = pricingMonth;
    }
  }

  /**
   * 部品上下文条目 —— 变量元信息 + 绑定策略 JSON 原文。
   *
   * <p>{@link #binding} 即 {@code lp_price_variable.context_binding_json}，形如：
   * <pre>{"source":"ENTITY","entity":"linkedItem","field":"blankWeight","unitScale":0.001}</pre>
   * 或 <pre>{"source":"DERIVED","strategy":"MAIN_MATERIAL_FINANCE"}</pre>
   * 前端不解析只原样透传给编辑器 tooltip 展示。
   */
  public static class PartContext {
    /** 变量编码，如 "blank_weight"、"material_price_incl"。 */
    private String code;

    /** 变量中文名，如 "下料重量"、"材料含税价格"。 */
    private String name;

    /** 绑定策略 JSON 原文，可能为 null（旧 seed 未补齐）。 */
    private String binding;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getBinding() {
      return binding;
    }

    public void setBinding(String binding) {
      this.binding = binding;
    }
  }

  /**
   * 公式引用条目 —— 可复用的计算式变量。
   *
   * <p>{@link #formulaExpr} 是原始表达式（可能含中文别名），前端在引用时由
   * {@code FormulaNormalizer} 重新规范化。
   */
  public static class FormulaRef {
    /** 变量编码，如 "Cu_excl"。 */
    private String code;

    /** 变量中文名，如 "不含税电解铜"。 */
    private String name;

    /** 公式表达式原文，如 "[Cu]/(1+[vat_rate])"。 */
    private String formulaExpr;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getFormulaExpr() {
      return formulaExpr;
    }

    public void setFormulaExpr(String formulaExpr) {
      this.formulaExpr = formulaExpr;
    }
  }
}
