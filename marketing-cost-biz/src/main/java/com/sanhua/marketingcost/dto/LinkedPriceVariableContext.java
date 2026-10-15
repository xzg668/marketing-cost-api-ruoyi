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
  private String factorSource;

  public String getFactorSource() {
    return factorSource;
  }

  public LinkedPriceVariableContext factorSource(String value) {
    factorSource = value;
    return this;
  }

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
    // 客户场景仅覆盖 Cu；板换 SUS 由统一因素上下文按规格和招标条件读取。
    context.putOaLockedKg("Cu", oaForm.getCopperPrice());
    return context;
  }

  public static LinkedPriceVariableContext monthlyAdjust() {
    LinkedPriceVariableContext context =
        new LinkedPriceVariableContext(LinkedPriceCalcScene.MONTHLY_ADJUST);
    return context;
  }

  public LinkedPriceCalcScene getCalcScene() {
    return calcScene;
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

  /** 设置已确认单位为元/公斤的场景输入。 */
  public LinkedPriceVariableContext overrideKg(String code, BigDecimal value, String source) {
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
