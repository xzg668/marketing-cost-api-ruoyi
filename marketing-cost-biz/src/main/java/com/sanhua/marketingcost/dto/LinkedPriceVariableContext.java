package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 联动价变量上下文：统一承载本次计算的变量值覆盖和 trace 来源。 */
public class LinkedPriceVariableContext {
  private static final BigDecimal TON_TO_KG_DIVISOR = new BigDecimal("1000");

  private final LinkedPriceCalcScene calcScene;
  private Long adjustBatchId;
  private String pricingMonth;
  private final Map<String, BigDecimal> variableValues = new LinkedHashMap<>();
  private final Map<String, String> variableSources = new LinkedHashMap<>();

  private LinkedPriceVariableContext(LinkedPriceCalcScene calcScene) {
    this.calcScene = calcScene;
  }

  public static LinkedPriceVariableContext quote(OaForm oaForm) {
    LinkedPriceVariableContext context = new LinkedPriceVariableContext(LinkedPriceCalcScene.QUOTE);
    if (oaForm == null) {
      return context;
    }
    // 正常报价只能使用 OA 表头锁价作为显式覆盖；未锁价的变量才允许继续走后续基价回落。
    context.putOaLockedKg("Cu", oaForm.getCopperPrice());
    context.putOaLockedKg("Zn", oaForm.getZincPrice());
    context.putOaLockedKg("Al", oaForm.getAluminumPrice());
    context.putOaLockedKg("Ag", oaForm.getSilverPrice());
    context.putOaLockedKg("Au", oaForm.getGoldPrice());
    context.putOaLockedKg("SUS304", oaForm.getSus304Price());
    context.putOaLockedKg("SUS316L", oaForm.getSus316lPrice());
    return context;
  }

  public static LinkedPriceVariableContext monthlyAdjust(Long adjustBatchId) {
    LinkedPriceVariableContext context =
        new LinkedPriceVariableContext(LinkedPriceCalcScene.MONTHLY_ADJUST);
    context.adjustBatchId = adjustBatchId;
    return context;
  }

  public LinkedPriceCalcScene getCalcScene() {
    return calcScene;
  }

  public Long getAdjustBatchId() {
    return adjustBatchId;
  }

  public String getPricingMonth() {
    return pricingMonth;
  }

  public LinkedPriceVariableContext pricingMonth(String pricingMonth) {
    this.pricingMonth = pricingMonth;
    return this;
  }

  public Map<String, BigDecimal> getVariableValues() {
    return Collections.unmodifiableMap(variableValues);
  }

  public Map<String, String> getVariableSources() {
    return Collections.unmodifiableMap(variableSources);
  }

  public String getVariableSource(String code) {
    return code == null ? null : variableSources.get(code);
  }

  /**
   * 在已加载的 OA 锁价之上覆盖单个“元/公斤”变量。
   *
   * <p>财务场景只调用本方法覆盖 Cu，因此不会清空或改写 OA 中的 Zn、Al 等锁价。
   */
  public LinkedPriceVariableContext overrideKg(
      String code, BigDecimal value, String source) {
    if (code == null || code.trim().isEmpty()) {
      throw new IllegalArgumentException("变量编码不能为空");
    }
    if (value == null) {
      throw new IllegalArgumentException("变量值不能为空");
    }
    String normalizedCode = code.trim();
    variableValues.put(normalizedCode, value);
    variableSources.put(normalizedCode, source == null ? null : source.trim());
    return this;
  }

  private void putOaLockedKg(String code, BigDecimal tonPrice) {
    if (tonPrice == null) {
      return;
    }
    variableValues.put(code, tonPrice.divide(TON_TO_KG_DIVISOR, 6, RoundingMode.HALF_UP));
    variableSources.put(code, calcScene.getDefaultFactorSource().getCode());
  }
}
