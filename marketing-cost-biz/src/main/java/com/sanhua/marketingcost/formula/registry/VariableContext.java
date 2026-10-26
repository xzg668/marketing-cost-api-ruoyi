package com.sanhua.marketingcost.formula.registry;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 变量解析上下文 —— 一次取价请求内共享的元数据与缓存。
 *
 * <p>使用 builder 风格设值：每次计算创建独立实例，调用方填充 oaNo/materialCode/quoteDate 等字段；resolver 通过 ctx
 * 拿到一次取价的"全部环境"，避免向每个 resolve 方法传 N 个参数。
 *
 * <p>包含两类数据：
 *
 * <ul>
 *   <li><b>请求范围</b>：oaNo / materialCode / quoteDate / pricingMonth — 由调用方传入
 *   <li><b>已解析快照</b>：oaForm / linkedItem — 调用方提前批量加载，避免 resolver 内部 N+1
 * </ul>
 *
 * <p>{@link #overrides} 用于显式覆盖某个变量值（测试 / 临时回填）。
 */
public class VariableContext {

  public enum PriceContextType {
    DAILY_QUOTE,
    MONTHLY_REPRICE
  }

  // 限于本次计算；新请求（包括 worker）重新读取已提交定义和绑定。
  Map<String, com.sanhua.marketingcost.entity.PriceVariable> variableDefinitions;
  final Map<Long, Map<String, com.sanhua.marketingcost.entity.PriceVariableBinding>> bindings =
      new HashMap<>();

  private String oaNo;
  private String materialCode;
  private LocalDate quoteDate;
  private String pricingMonth;
  private PriceContextType priceContextType = PriceContextType.DAILY_QUOTE;
  private OaForm oaForm;
  private PriceLinkedItem linkedItem;
  private SupplierPriceReviewContext supplierReviews;
  private PreparedFormulaInputs preparedInputs;
  private final Map<String, com.sanhua.marketingcost.entity.FactorMonthlyPrice> usedPrivateFactors = new java.util.LinkedHashMap<>();
  private final Map<Long, com.sanhua.marketingcost.entity.FactorIdentity> usedFactorIdentities = new java.util.LinkedHashMap<>();
  private final Map<String, java.util.List<com.sanhua.marketingcost.entity.MaterialScrapRef>> usedScrapReferences = new java.util.LinkedHashMap<>();

  public Map<String, com.sanhua.marketingcost.entity.FactorMonthlyPrice> getUsedPrivateFactors() { return usedPrivateFactors; }
  public Map<Long, com.sanhua.marketingcost.entity.FactorIdentity> getUsedFactorIdentities() { return usedFactorIdentities; }
  public Map<String, java.util.List<com.sanhua.marketingcost.entity.MaterialScrapRef>> getUsedScrapReferences() { return usedScrapReferences; }

  public PreparedFormulaInputs getPreparedInputs() { return preparedInputs; }

  public VariableContext preparedInputs(PreparedFormulaInputs value) {
    preparedInputs = java.util.Objects.requireNonNull(value);
    variableDefinitions = value.calculationVariables();
    return this;
  }

  public SupplierPriceReviewContext getSupplierReviews() {
    return supplierReviews;
  }

  public VariableContext supplierReviews(SupplierPriceReviewContext value) {
    supplierReviews = value;
    return this;
  }
  private final Map<String, BigDecimal> primitiveInputs = new java.util.LinkedHashMap<>();
  private final Map<String, Object> usedDefinitions = new java.util.LinkedHashMap<>();
  private final java.util.Set<String> usedFinanceKeys = new java.util.LinkedHashSet<>();
  private final Map<String, String> scrapPriceInputs = new java.util.LinkedHashMap<>();

  public Map<String, String> getScrapPriceInputs() {
    return scrapPriceInputs;
  }

  public Map<String, BigDecimal> getPrimitiveInputs() {
    return primitiveInputs;
  }

  public Map<String, Object> getUsedDefinitions() {
    return usedDefinitions;
  }

  public java.util.Set<String> getUsedFinanceKeys() {
    return usedFinanceKeys;
  }

  private boolean financeQuoteBase;
  private com.sanhua.marketingcost.service.pricing.PriceFactorContext priceFactors;
  private final Map<String, String> resolvedFactorKeys = new java.util.LinkedHashMap<>();

  public boolean isFinanceQuoteBase() {
    return financeQuoteBase;
  }

  public VariableContext financeQuoteBase(boolean value) {
    financeQuoteBase = value;
    return this;
  }

  public com.sanhua.marketingcost.service.pricing.PriceFactorContext getPriceFactors() {
    return priceFactors;
  }

  public void setPriceFactors(com.sanhua.marketingcost.service.pricing.PriceFactorContext value) {
    priceFactors = value;
  }

  public Map<String, String> getResolvedFactorKeys() {
    return resolvedFactorKeys;
  }

  /** 显式覆盖某变量值（优先级最高） */
  private final Map<String, BigDecimal> overrides = new HashMap<>();

  private final Map<String, String> overrideSources = new HashMap<>();
  private final Map<String, String> resolvedSources = new HashMap<>();

  public String getOaNo() {
    return oaNo;
  }

  public VariableContext oaNo(String oaNo) {
    this.oaNo = oaNo;
    return this;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public VariableContext materialCode(String materialCode) {
    this.materialCode = materialCode;
    return this;
  }

  public LocalDate getQuoteDate() {
    return quoteDate;
  }

  public VariableContext quoteDate(LocalDate quoteDate) {
    this.quoteDate = quoteDate;
    return this;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public VariableContext pricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
    return this;
  }

  public PriceContextType getPriceContextType() {
    return priceContextType;
  }

  public VariableContext priceContextType(PriceContextType priceContextType) {
    this.priceContextType =
        priceContextType == null ? PriceContextType.DAILY_QUOTE : priceContextType;
    return this;
  }

  public boolean isMonthlyReprice() {
    return PriceContextType.MONTHLY_REPRICE.equals(priceContextType);
  }

  public OaForm getOaForm() {
    return oaForm;
  }

  public VariableContext oaForm(OaForm oaForm) {
    this.oaForm = oaForm;
    return this;
  }

  public PriceLinkedItem getLinkedItem() {
    return linkedItem;
  }

  public VariableContext linkedItem(PriceLinkedItem linkedItem) {
    this.linkedItem = linkedItem;
    return this;
  }

  public Map<String, BigDecimal> getOverrides() {
    return overrides;
  }

  public Map<String, String> getOverrideSources() {
    return overrideSources;
  }

  public String getOverrideSource(String code) {
    return code == null ? null : overrideSources.get(code);
  }

  public Map<String, String> getResolvedSources() {
    return resolvedSources;
  }

  public String getResolvedSource(String code) {
    return code == null ? null : resolvedSources.get(code);
  }

  public VariableContext resolvedSource(String code, String source) {
    if (code != null && source != null && !source.isBlank()) {
      resolvedSources.put(code, source);
    }
    return this;
  }

  public VariableContext override(String code, BigDecimal value) {
    if (code != null) {
      overrides.put(code, value);
    }
    return this;
  }

  public VariableContext override(String code, BigDecimal value, String source) {
    override(code, value);
    if (code != null && source != null && !source.isBlank()) {
      overrideSources.put(code, source);
    }
    return this;
  }
}
