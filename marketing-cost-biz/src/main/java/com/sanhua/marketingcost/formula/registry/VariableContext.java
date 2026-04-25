package com.sanhua.marketingcost.formula.registry;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 变量解析上下文 —— 一次取价请求内共享的元数据与缓存。
 *
 * <p>使用 builder 风格设值：上下文实例可重用，调用方按需填充 oaNo/materialCode/quoteDate
 * 等字段；resolver 通过 ctx 拿到一次取价的"全部环境"，避免向每个 resolve 方法传 N 个参数。
 *
 * <p>包含两类数据：
 * <ul>
 *   <li><b>请求范围</b>：oaNo / materialCode / quoteDate / pricingMonth — 由调用方传入</li>
 *   <li><b>已解析快照</b>：oaForm / linkedItem — 调用方提前批量加载，避免 resolver 内部 N+1</li>
 * </ul>
 *
 * <p>{@link #overrides} 用于显式覆盖某个变量值（测试 / 临时回填）。
 */
public class VariableContext {

  private String oaNo;
  private String materialCode;
  private LocalDate quoteDate;
  private String pricingMonth;
  private OaForm oaForm;
  private PriceLinkedItem linkedItem;

  /** 显式覆盖某变量值（优先级最高） */
  private final Map<String, BigDecimal> overrides = new HashMap<>();

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

  public VariableContext override(String code, BigDecimal value) {
    if (code != null) {
      overrides.put(code, value);
    }
    return this;
  }
}
